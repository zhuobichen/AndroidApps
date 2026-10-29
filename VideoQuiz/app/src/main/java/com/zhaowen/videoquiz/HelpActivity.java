package com.zhaowen.videoquiz;

import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;

/**
 * 内置使用说明：不需要联网，也不需要外部文档。
 * 内容按「是什么 → 首次准备 → 日常流程 → 进阶 → 排错」组织。
 */
public class HelpActivity extends AppCompatActivity {

    private final List<String[]> blocks = new ArrayList<>();

    private void heading(String text) {
        blocks.add(new String[]{"h", text});
    }

    private void para(String text) {
        blocks.add(new String[]{"p", text});
    }

    private void bullet(String text) {
        blocks.add(new String[]{"b", text});
    }

    private void space() {
        blocks.add(new String[]{"s", ""});
    }

    private void buildContent() {
        heading("这个东西是干嘛的");
        para("卷灵把一段试卷录像变成答案和 PDF。");
        space();
        para("手机只负责拍和传；真正读题、解题、排版都在你自己的电脑上跑（DSH + 桥接），"
                + "所以视频不会上传到任何第三方服务器，答案也不会。");
        space();
        para("典型场景：整卷考试后讲解、错题复盘、把老师讲评的视频整理成文字稿。");

        heading("第一次用：电脑端");
        para("1. 电脑上装好 DSH（DeepSeek Harness），能正常打开网页界面。");
        para("2. 启动桥接服务。最省事的方式是双击 bridge\\start.cmd，它已经带好了工作区锁定和双栈监听。");
        para("3. 桥接启动后会在 bridge\\data\\token.txt 生成一串配对令牌，这串字符就是手机的钥匙，复制下来备用。");
        para("4. 首次运行如果 Windows 防火墙弹窗，选择允许（专用网络即可）。");
        space();
        bullet("桥接是 DSH 的子进程，DSH 重启时它会一起退出，重新双击 start.cmd 即可。");
        bullet("手机和电脑必须在同一个网络里。校园网常做客户端隔离，IPv4 可能不通，改用 IPv6 地址通常可以。");

        heading("第一次用：手机端");
        para("1. 打开设置，确认在「电脑 Harness」这一页。");
        para("2. 「电脑地址」填 http://电脑IP:8765；IPv6 写成 http://[2400:xxxx:...]:8765，方括号不能省。");
        para("3. 「配对令牌」粘贴 token.txt 里的那串字符（注意别带上换行）。");
        para("4. 点「测试连接」，出现成功提示就说明通了。");
        para("5. 「选择目标会话」可以挑已有会话，也可以新建一个自己命名的。");

        heading("日常怎么用");
        para("1. 点「选择视频」，挑一段试卷录像。");
        para("2. 点「上传到电脑」，整段视频一次性传完，不分片。");
        para("3. 传完默认不会自动开始，界面会提示「点开始处理才发给会话」，确认无误后点「开始处理」。");
        para("4. 电脑开始解析。状态点变蓝是处理中，变绿是完成。");
        para("5. 完成后点「打开 PDF」，内置阅读器直接打开，不用装第三方软件。");
        space();
        bullet("想让它传完自动开始？设置里勾上「上传后立即派发任务」。");
        bullet("50MB 的整卷大概十几分钟。可以退出 App，回来会自动接上。");

        heading("追问：结果出来以后");
        para("结果出来之后，底部会多一个「继续追问」按钮。");
        space();
        para("点进去是独立的对话页。你的问题会发给处理这份卷子的同一个会话，"
                + "它记得刚才是怎么解的，不用重新看视频。");
        space();
        bullet("追问不会重新生成 PDF。想要新 PDF 得重新导出。");
        bullet("页面顶部会写明这次追问发到哪个会话，避免你以为是别的地方。");

        heading("任务队列：同时跑多个任务");
        para("顶栏点「任务」，可以看到电脑上所有任务，每条带状态和归属会话。");
        space();
        bullet("待处理 → 开始处理");
        bullet("处理中 → 取消");
        bullet("已中断 → 重新派发（会强制重投，不会因为投过就跳过）");
        bullet("已完成 → 打开结果 / 追问");
        bullet("已取消 → 恢复");
        space();
        para("多个任务可以同时跑，互不影响。列表每 3 秒自动刷新。");

        heading("历史任务");
        para("顶栏点「历史」，列出最近 30 个任务。");
        space();
        para("点已完成的那条会把结果重新取回手机。之前下过的文件会直接复用本地副本，不重复下载。");
        space();
        bullet("结果文件放在 App 私有目录，卸载才会清除。");
        bullet("换手机或清数据后，仍可从电脑重新下载。");

        heading("出问题了怎么办");
        para("连不上电脑");
        bullet("确认电脑上桥接在跑（黑窗口没关）。");
        bullet("确认手机和电脑同一个网络。");
        bullet("校园网/公共 WiFi 常做隔离，IPv4 不通是常态，换 IPv6 地址。");
        bullet("地址末尾的 :8765 别丢。");
        space();
        para("一直显示处理中");
        bullet("去「任务」页看那条任务的状态。显示「已中断」说明负责它的会话没在跑。");
        bullet("点「重新派发」，它会强制重投一次。");
        space();
        para("写文件时要我点批准");
        bullet("新会话的默认权限可能不是「完全权限」。");
        bullet("在 DSH 网页界面里把新会话权限设成完全权限，或者用权限下拉改一下。");
        space();
        para("PDF 打不开");
        bullet("先确认任务状态是「已完成」。");
        bullet("内置阅读器解析失败时会提示，可以重新下载试试。");

        heading("隐私与数据");
        bullet("视频只在你自己的电脑上，不上传第三方。");
        bullet("手机端只保存配对令牌、目标会话选择和已下载的结果文件。");
        bullet("配对令牌相当于密码，别发给别人。泄露了就在电脑上删掉 token.txt 重启桥接换新的。");

        heading("版本");
        para("当前 " + versionName() + "。源码与更新：github.com/zhuobichen/AndroidApps");
    }

    private String versionName() {
        try {
            return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
        } catch (Throwable e) {
            return "未知";
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_help);

        LinearLayout body = findViewById(R.id.helpBody);
        if (body == null) return;

        buildContent();

        float dp = getResources().getDisplayMetrics().density;

        for (String[] block : blocks) {
            String kind = block[0];
            String text = block[1];

            if ("s".equals(kind)) {
                TextView gap = new TextView(this);
                gap.setText("");
                gap.setTextSize(4);
                body.addView(gap, new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, (int) (6 * dp)));
                continue;
            }

            TextView view = new TextView(this);
            view.setText(text);
            view.setLineSpacing((int) (4 * dp), 1f);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);

            if ("h".equals(kind)) {
                view.setTextSize(16);
                view.setTextColor(getColor(R.color.text_primary));
                view.getPaint().setFakeBoldText(true);
                lp.topMargin = (int) (22 * dp);
            } else if ("b".equals(kind)) {
                view.setText("· " + text);
                view.setTextSize(13.5f);
                view.setTextColor(getColor(R.color.text_secondary));
                lp.topMargin = (int) (5 * dp);
                lp.leftMargin = (int) (6 * dp);
            } else {
                view.setTextSize(13.5f);
                view.setTextColor(getColor(R.color.text_primary));
                lp.topMargin = (int) (8 * dp);
            }

            body.addView(view, lp);
        }
    }
}
