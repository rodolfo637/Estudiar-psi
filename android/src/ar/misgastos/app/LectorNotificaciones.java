package ar.misgastos.app;

import android.app.Notification;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Guarda el texto de las notificaciones de Mercado Pago para que la app las convierta en movimientos.
 * Ignora las notificaciones de cualquier otra app. Los textos quedan solo en el teléfono.
 */
public class LectorNotificaciones extends NotificationListenerService {
    private static final Object LOCK = new Object();
    private static final int MAXIMO = 200;

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        try {
            String pkg = sbn.getPackageName();
            if (pkg == null || !pkg.contains("mercadopago")) return;
            Notification n = sbn.getNotification();
            if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
            Bundle x = n.extras;
            if (x == null) return;
            CharSequence t = x.getCharSequence(Notification.EXTRA_TITLE);
            CharSequence big = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
            CharSequence txt = big != null ? big : x.getCharSequence(Notification.EXTRA_TEXT);
            if (t == null && txt == null) return;
            long cuando = n.when > 0 ? n.when : sbn.getPostTime();
            guardar(this, t == null ? "" : t.toString(), txt == null ? "" : txt.toString(), cuando);
        } catch (Exception e) {
            // Una notificación con un formato inesperado no debe frenar el servicio.
        }
    }

    static void guardar(Context c, String titulo, String texto, long cuando) throws Exception {
        synchronized (LOCK) {
            SharedPreferences sp = c.getSharedPreferences("misgastos", Context.MODE_PRIVATE);
            JSONArray a = new JSONArray(sp.getString("notifs", "[]"));
            String clave = titulo + "|" + texto + "|" + cuando;   // una notificación actualizada no se cuenta dos veces
            for (int i = 0; i < a.length(); i++) {
                if (clave.equals(a.getJSONObject(i).optString("key"))) return;
            }
            JSONObject o = new JSONObject();
            o.put("title", titulo);
            o.put("text", texto);
            o.put("ts", cuando);
            o.put("key", clave);
            a.put(o);
            JSONArray b = a;
            if (a.length() > MAXIMO) {
                b = new JSONArray();
                for (int i = a.length() - MAXIMO; i < a.length(); i++) b.put(a.get(i));
            }
            sp.edit().putString("notifs", b.toString()).commit();
        }
    }

    /** Devuelve las notificaciones pendientes y vacía la lista. */
    static String tomar(Context c) {
        synchronized (LOCK) {
            SharedPreferences sp = c.getSharedPreferences("misgastos", Context.MODE_PRIVATE);
            String s = sp.getString("notifs", "[]");
            sp.edit().putString("notifs", "[]").commit();
            return s;
        }
    }
}
