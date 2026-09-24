package com.mail.mail.dto;

import lombok.Data;

@Data
public class UpdateFlagsRequest {
    private Boolean read;
    private Boolean starred;
    private Boolean important;
}