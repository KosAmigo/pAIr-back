package com.pair.domain.exam.service;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import com.pair.domain.exam.dto.StreamFrameDto;

/**
 * 상시 저장 묶음(.pack) 형식 (big-endian, 계약 §6-1):
 * "PAIRPK"(6) · version(1) · channel(1: 1=webcam 2=phone) · count(2) · startMs(8) · count × {offsetMs(4), length(4)} · JPEG 본문 이어 붙임.
 * 읽는 쪽은 magic·version 이 다르면 IllegalArgumentException 으로 그 묶음을 건너뛴다.
 */
public final class StreamPackCodecService {

    private static final byte[] MAGIC = "PAIRPK".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 1;
    private static final int HEADER = 18;
    /** 묶음 하나에 들어갈 수 있는 사진 수 (10초 구간, 초당 1장) */
    public static final int MAX_FRAMES = 10;

    private StreamPackCodecService() {}

    public static byte[] pack(int channelCode, long startMs, List<StreamFrameDto> frames) {
        if (frames.isEmpty() || frames.size() > MAX_FRAMES) throw new IllegalArgumentException("묶음의 사진 수가 올바르지 않습니다.");
        int total = 0;
        for (StreamFrameDto f : frames) total += f.jpeg().length;
        ByteBuffer out = ByteBuffer.allocate(HEADER + frames.size() * 8 + total);
        out.put(MAGIC).put((byte) VERSION).put((byte) channelCode).putShort((short) frames.size()).putLong(startMs);
        for (StreamFrameDto f : frames) {
            out.putInt((int) (f.atMs() - startMs));
            out.putInt(f.jpeg().length);
        }
        for (StreamFrameDto f : frames) out.put(f.jpeg());
        return out.array();
    }

    public static List<StreamFrameDto> unpack(byte[] bytes) {
        if (bytes.length < HEADER) throw new IllegalArgumentException("묶음 파일 형식이 올바르지 않습니다.");
        ByteBuffer in = ByteBuffer.wrap(bytes);
        byte[] magic = new byte[MAGIC.length];
        in.get(magic);
        if (!java.util.Arrays.equals(magic, MAGIC) || (in.get() & 0xFF) != VERSION) throw new IllegalArgumentException("지원하지 않는 묶음 파일입니다.");
        in.get(); // channel
        int count = in.getShort() & 0xFFFF;
        long startMs = in.getLong();
        if (count < 1 || count > MAX_FRAMES || in.remaining() < count * 8) throw new IllegalArgumentException("묶음 파일 형식이 올바르지 않습니다.");
        int[] offsets = new int[count];
        int[] lengths = new int[count];
        for (int i = 0; i < count; i++) {
            offsets[i] = in.getInt();
            lengths[i] = in.getInt();
        }
        List<StreamFrameDto> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            if (lengths[i] < 0 || lengths[i] > in.remaining()) throw new IllegalArgumentException("묶음 파일 형식이 올바르지 않습니다.");
            byte[] jpeg = new byte[lengths[i]];
            in.get(jpeg);
            out.add(new StreamFrameDto(startMs + offsets[i], jpeg));
        }
        return out;
    }
}
