package com.pair.domain.taskgen.dto;

import java.util.List;

/**
 * 단계 4'(관련 적은 자료) 결과(E6·M6). files 의 path 는 설계도 fillers 의 path 와 같아야 한다.
 * DEV 는 컴파일되는 Java 파일 내용, BIZ 는 .pdf(글, 쪽 나누기는 코드가 한다)나 .csv 내용이다.
 */
public record FillerDto(List<DevDraftDto.FileDraft> files) {}
