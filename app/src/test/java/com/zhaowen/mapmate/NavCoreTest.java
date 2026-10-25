package com.zhaowen.mapmate;

/** 纯 JVM 测试：用真实高德路线数据验证 NavCore。 */
public class NavCoreTest {

    private static int pass = 0, fail = 0;

    public static void main(String[] args) {
        double[] pts = NavData.PTS;

        double len = NavCore.length(pts);
        check("折线长度与 API 距离一致", Math.abs(len - NavData.API_DISTANCE) / NavData.API_DISTANCE < 0.03,
                String.format("core=%.0fm api=%dm", len, NavData.API_DISTANCE));

        double[] atStart = NavCore.alongOffset(pts[0], pts[1], pts);
        check("起点：里程≈0 且贴线", atStart[0] < 30 && atStart[1] < 5,
                String.format("along=%.0f off=%.1f", atStart[0], atStart[1]));

        int last = pts.length - 2;
        double[] atEnd = NavCore.alongOffset(pts[last], pts[last + 1], pts);
        check("终点：里程≈全长 且贴线", Math.abs(atEnd[0] - len) < 30 && atEnd[1] < 5,
                String.format("along=%.0f off=%.1f", atEnd[0], atEnd[1]));

        check("终点判定：已到达", NavCore.arrived(pts[last], pts[last + 1], pts, atEnd[0], 60), "");

        double midLat = pts[100], midLng = pts[101];
        double[] mid = NavCore.alongOffset(midLat, midLng, pts);
        check("中途点贴线", mid[1] < 5, String.format("off=%.1f", mid[1]));

        // 沿当前路段法线方向偏移约 300 米，应判为偏航
        double segLat = pts[102] - pts[100];
        double segLng = pts[103] - pts[101];
        double norm = Math.hypot(segLat, segLng);
        double d = 0.0027; // 约 300m
        double offLat = midLat + (-segLng / norm) * d;
        double offLng = midLng + (segLat / norm) * d;
        double[] off = NavCore.alongOffset(offLat, offLng, pts);
        check("偏航检测 > 80m", off[1] > 80, String.format("off=%.0fm", off[1]));

        // 转向点里程单调递增
        double prev = -1; boolean mono = true;
        for (int i = 0; i < NavData.MANEUVERS.length; i += 2) {
            double a = NavCore.alongOffset(NavData.MANEUVERS[i], NavData.MANEUVERS[i + 1], pts)[0];
            if (a < prev - 20) { mono = false; break; }
            prev = a;
        }
        check("转向点里程递增", mono, "");

        int idx0 = NavCore.nextIndex(new double[]{100, 500, 900}, 0, 8);
        check("nextIndex 取第一个前方点", idx0 == 0, "idx=" + idx0);
        int idx1 = NavCore.nextIndex(new double[]{100, 500, 900}, 200, 8);
        check("nextIndex 跳过已过点", idx1 == 1, "idx=" + idx1);
        int idx2 = NavCore.nextIndex(new double[]{100, 500, 900}, 950, 8);
        check("nextIndex 末尾返回 -1", idx2 == -1, "idx=" + idx2);

        check("档位 900→500", NavCore.bucket(900) == 500, "");
        check("档位 450→200", NavCore.bucket(450) == 200, "");
        check("档位 150→50", NavCore.bucket(150) == 50, "");
        check("档位 30→0", NavCore.bucket(30) == 0, "");

        System.out.printf("%n结果: %d passed, %d failed%n", pass, fail);
        if (fail > 0) System.exit(1);
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) { pass++; System.out.printf("PASS  %-24s %s%n", name, detail); }
        else { fail++; System.out.printf("FAIL  %-24s %s%n", name, detail); }
    }
}