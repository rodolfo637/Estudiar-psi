package ar.misgastos.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.speech.RecognizerIntent;
import android.webkit.JavascriptInterface;
import android.webkit.JsResult;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;

/** Envoltorio nativo de gastos.html: voz, guardado de archivos, cotización y diálogos. */
public class MainActivity extends Activity {
    private static final String INICIO = "file:///android_asset/gastos.html";
    private static final int REQ_VOZ = 1, REQ_ARCHIVO = 2, REQ_GUARDAR = 3;

    private WebView web;
    private ValueCallback<Uri[]> archivoCb;
    private String contenidoPendiente;

    @Override
    protected void onCreate(Bundle estado) {
        super.onCreate(estado);
        getWindow().setStatusBarColor(0xFF0F1420);
        getWindow().setNavigationBarColor(0xFF0F1420);

        web = new WebView(this);
        web.setBackgroundColor(0xFF0F1420);
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);   // localStorage: acá se guardan los movimientos
        s.setDatabaseEnabled(true);

        web.addJavascriptInterface(new Puente(), "AndroidVoz");
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String url) {
                if (url.startsWith("file:///android_asset/")) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { /* sin navegador */ }
                return true;
            }
        });
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onJsAlert(WebView v, String url, String msg, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg).setCancelable(false)
                    .setPositiveButton("Aceptar", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { r.confirm(); }
                    }).show();
                return true;
            }

            @Override
            public boolean onJsConfirm(WebView v, String url, String msg, final JsResult r) {
                new AlertDialog.Builder(MainActivity.this).setMessage(msg).setCancelable(false)
                    .setPositiveButton("Sí", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { r.confirm(); }
                    })
                    .setNegativeButton("No", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { r.cancel(); }
                    }).show();
                return true;
            }

            @Override
            public boolean onShowFileChooser(WebView v, ValueCallback<Uri[]> cb, FileChooserParams p) {
                if (archivoCb != null) archivoCb.onReceiveValue(null);
                archivoCb = cb;
                Intent i = new Intent(Intent.ACTION_GET_CONTENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("*/*");
                try {
                    startActivityForResult(Intent.createChooser(i, "Elegí el respaldo"), REQ_ARCHIVO);
                } catch (ActivityNotFoundException e) {
                    archivoCb = null;
                    return false;
                }
                return true;
            }
        });

        if (estado == null) web.loadUrl(INICIO); else web.restoreState(estado);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        web.saveState(out);
    }

    /** Ejecuta JavaScript en la página desde cualquier hilo. */
    private void js(final String codigo) {
        runOnUiThread(new Runnable() {
            public void run() { web.evaluateJavascript(codigo, null); }
        });
    }

    private void aviso(final String msg) {
        runOnUiThread(new Runnable() {
            public void run() { Toast.makeText(MainActivity.this, msg, Toast.LENGTH_LONG).show(); }
        });
    }

    /** Métodos que la página llama como AndroidVoz.xxx(). */
    private class Puente {
        @JavascriptInterface
        public void escuchar() {
            runOnUiThread(new Runnable() {
                public void run() {
                    Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
                    i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
                    i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-AR");
                    i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "es-AR");
                    i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
                    i.putExtra(RecognizerIntent.EXTRA_PROMPT, "Decí tu movimiento: “gasté 15 mil en supermercado”");
                    try {
                        startActivityForResult(i, REQ_VOZ);
                    } catch (ActivityNotFoundException e) {
                        js("window.__vozError(" + JSONObject.quote("Este teléfono no tiene reconocimiento de voz. Instalá o activá la app de Google, o escribí la frase.") + ")");
                    }
                }
            });
        }

        @JavascriptInterface
        public void guardar(final String nombre, final String contenido, final String mime) {
            runOnUiThread(new Runnable() {
                public void run() {
                    contenidoPendiente = contenido;
                    String tipo = mime == null ? "*/*" : mime.split(";")[0].trim();
                    Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType(tipo);
                    i.putExtra(Intent.EXTRA_TITLE, nombre);
                    try {
                        startActivityForResult(i, REQ_GUARDAR);
                    } catch (ActivityNotFoundException e) {
                        contenidoPendiente = null;
                        aviso("No encontré una app para guardar archivos");
                    }
                }
            });
        }

        @JavascriptInterface
        public void cotizar(final String tipo) {
            if (tipo == null || !tipo.matches("[a-z]{1,30}")) { js("window.__cotizErr()"); return; }
            new Thread(new Runnable() {
                public void run() {
                    HttpURLConnection c = null;
                    try {
                        c = (HttpURLConnection) new URL("https://dolarapi.com/v1/dolares/" + tipo).openConnection();
                        c.setConnectTimeout(10000);
                        c.setReadTimeout(10000);
                        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
                        InputStream in = c.getInputStream();
                        ByteArrayOutputStream buf = new ByteArrayOutputStream();
                        byte[] b = new byte[4096];
                        int n;
                        while ((n = in.read(b)) > 0 && buf.size() < 65536) buf.write(b, 0, n);
                        in.close();
                        js("window.__cotizOk(" + JSONObject.quote(buf.toString("UTF-8")) + ")");
                    } catch (Exception e) {
                        js("window.__cotizErr()");
                    } finally {
                        if (c != null) c.disconnect();
                    }
                }
            }).start();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_VOZ) {
            if (res == RESULT_OK && data != null) {
                ArrayList<String> r = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
                if (r != null && !r.isEmpty()) js("window.__vozResultado(" + JSONObject.quote(r.get(0)) + ")");
            }
        } else if (req == REQ_ARCHIVO) {
            if (archivoCb != null) {
                Uri u = (res == RESULT_OK && data != null) ? data.getData() : null;
                archivoCb.onReceiveValue(u == null ? null : new Uri[]{u});
                archivoCb = null;
            }
        } else if (req == REQ_GUARDAR) {
            String contenido = contenidoPendiente;
            contenidoPendiente = null;
            if (res == RESULT_OK && data != null && data.getData() != null && contenido != null) {
                try {
                    OutputStream o = getContentResolver().openOutputStream(data.getData());
                    o.write(contenido.getBytes("UTF-8"));
                    o.close();
                    aviso("Archivo guardado");
                } catch (Exception e) {
                    aviso("No pude guardar el archivo");
                }
            }
        }
    }

    @Override
    public void onBackPressed() {
        // Atrás cierra primero la ventana de carga; si no hay, sale de la app.
        web.evaluateJavascript(
            "(function(){var m=document.getElementById('modal');if(m&&m.classList.contains('on')){m.classList.remove('on');return 1;}return 0;})()",
            new ValueCallback<String>() {
                public void onReceiveValue(String v) {
                    if (!"1".equals(v)) MainActivity.super.onBackPressed();
                }
            });
    }
}
