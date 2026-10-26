package com.zhaowen.videoquiz;

import java.util.ArrayList;
import java.util.List;

/** 一道题。 */
public class Question {
    public int no;
    public String stem = "";
    public List<String> options = new ArrayList<>();
    public String answer = "";
    public String reason = "";
    public int firstFrame = 0;
    public double sharp = 0;
    public String blankSentence = "";

    public String toDisplay() {
        StringBuilder sb = new StringBuilder();
        sb.append(no).append(". ").append(stem).append('\n');
        for (String o : options) sb.append(o).append('\n');
        if (!answer.isEmpty()) {
            sb.append("答案：").append(answer);
            if (!reason.isEmpty()) sb.append("　").append(reason);
            sb.append('\n');
        }
        return sb.toString();
    }
}