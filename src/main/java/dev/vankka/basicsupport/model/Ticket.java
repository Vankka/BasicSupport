package dev.vankka.basicsupport.model;

import lombok.Data;

import java.util.List;

@Data
public class Ticket {

    private final String channelId;
    private final String ownerId;
    private final String message;
    private final List<String> extraDms;
}
