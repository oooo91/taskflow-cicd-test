package com.domain.taskflow.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

public class JobCreateRequest {

    // 멱등키
    public String jobKey;

    @NotBlank
    public String type;

    // 일단 String 으로 받자 (클라이언트가 json 문자열로 보내도 되고, 객체로 보내면 jackson이 문자열로 못 바꿈)
    // 제일 쉬운 방식: payload 는 "문자열(json)"로 받기
    @NotNull
    public String payload;

    public OffsetDateTime scheduledAt;
}
