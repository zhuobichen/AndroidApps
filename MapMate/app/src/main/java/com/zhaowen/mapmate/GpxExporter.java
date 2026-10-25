package com.zhaowen.mapmate;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;

import com.amap.api.maps.model.LatLng;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/** 把骑行记录导出成 GPX，可导入 Strava / 行者 / 码表。 */
public class GpxExporter {

    public static File write(Activity ctx, RideTracker.Ride r) throws Exception {
        File dir = new File(ctx.getExternalFilesDir(null), "gpx");
        if (!dir.exists() && !dir.mkdirs()) throw new RuntimeException("无法创建目录");
        String name = "mapmate_"
                + new SimpleDateFormat("yyyyMMdd_HHmm", Locale.CHINA).format(new Date(r.startAt))
                + ".gpx";
        File file = new File(dir, name);

        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<gpx version=\"1.1\" creator=\"MapMate\" xmlns=\"http://www.topografix.com/GPX/1/1\">\n");
        sb.append("<trk><name>MapMate Ride</name><trkseg>\n");

        int n = r.points.size();
        long dur = Math.max(1, r.endAt - r.startAt);
        SimpleDateFormat iso = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
        iso.setTimeZone(TimeZone.getTimeZone("UTC"));
        for (int i = 0; i < n; i++) {
            LatLng p = r.points.get(i);
            long t = r.startAt + dur * i / Math.max(1, n - 1);
            sb.append("<trkpt lat=\"").append(p.latitude).append("\" lon=\"").append(p.longitude).append("\">")
              .append("<time>").append(iso.format(new Date(t))).append("</time>")
              .append("</trkpt>\n");
        }
        sb.append("</trkseg></trk></gpx>\n");

        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        }
        return file;
    }

    public static void share(Activity ctx, File file) {
        Uri uri = androidx.core.content.FileProvider.getUriForFile(
                ctx, "com.zhaowen.mapmate.fileprovider", file);
        Intent share = new Intent(Intent.ACTION_SEND);
        share.setType("application/gpx+xml");
        share.putExtra(Intent.EXTRA_STREAM, uri);
        share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        ctx.startActivity(Intent.createChooser(share, "导出 GPX"));
    }
}