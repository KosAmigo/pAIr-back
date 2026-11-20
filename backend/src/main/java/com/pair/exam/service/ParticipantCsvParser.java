package com.pair.exam.service;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 대상자 CSV 읽기 (사이트가 주는 양식: 첫 행 "이름,이메일"). 열 순서는 상관없고 영문 name/email 도 된다.
 * 인코딩은 UTF-8(BOM 있어도 됨)을 먼저 보고, 깨지면 엑셀 한글판 기본값인 MS949 로 읽는다.
 * 따옴표로 감싼 칸("홍, 길동")과 칸 안의 "" 도 읽는다.
 * 검증(이메일 형식, 중복)은 여기서 하지 않고 CSV 줄 번호만 붙여 돌려준다.
 */
public final class ParticipantCsvParser {

    public record CsvRow(int line, String name, String email) {}

    private static final Charset MS949 = Charset.forName("MS949");
    private static final char BOM = '﻿';

    private ParticipantCsvParser() {}

    public static List<CsvRow> parse(byte[] bytes, int maxRows) {
        if (ExamWorkbooks.isXlsx(bytes)) return ExamWorkbooks.readParticipants(bytes, maxRows);
        String text = decode(bytes);
        if (!text.isEmpty() && text.charAt(0) == BOM) text = text.substring(1);
        List<List<String>> records = records(text);
        if (records.isEmpty()) throw new IllegalArgumentException("CSV 가 비어 있습니다.");

        List<String> header = records.get(0);
        int nameCol = findColumn(header, "이름", "name");
        int emailCol = findColumn(header, "이메일", "email");
        if (nameCol < 0 || emailCol < 0) throw new IllegalArgumentException("첫 행에 '이름', '이메일' 열이 있어야 합니다. 양식을 내려받아 써 주세요.");

        List<CsvRow> rows = new ArrayList<>();
        for (int i = 1; i < records.size(); i++) {
            List<String> r = records.get(i);
            String name = cell(r, nameCol);
            String email = cell(r, emailCol);
            if (name.isEmpty() && email.isEmpty()) continue; // 빈 줄
            if (rows.size() >= maxRows) throw new IllegalArgumentException("한 번에 " + maxRows + "명까지 올릴 수 있습니다.");
            rows.add(new CsvRow(i + 1, name, email));
        }
        return rows;
    }

    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, MS949);
        }
    }

    /** RFC 4180 모양. 줄 번호를 맞추려고 레코드 단위로 나눈다 (따옴표 안 줄바꿈도 한 레코드) */
    static List<List<String>> records(String text) {
        List<List<String>> out = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    cell.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    cell.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                row.add(cell.toString());
                cell.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(cell.toString());
                cell.setLength(0);
                out.add(row);
                row = new ArrayList<>();
            } else {
                cell.append(c);
            }
        }
        if (quoted) throw new IllegalArgumentException("CSV 따옴표가 닫히지 않았습니다.");
        if (cell.length() > 0 || !row.isEmpty()) {
            row.add(cell.toString());
            out.add(row);
        }
        return out;
    }

    static int findColumn(List<String> header, String... names) {
        for (int i = 0; i < header.size(); i++) {
            // '이름 *', '이메일(필수)' 처럼 표시가 붙은 열 이름도 읽는다
            String v = header.get(i).replaceAll("\\(.*?\\)|[*\\s]", "").toLowerCase();
            for (String n : names) if (v.equals(n)) return i;
        }
        return -1;
    }

    private static String cell(List<String> r, int col) {
        return col < r.size() ? r.get(col).trim() : "";
    }
}
