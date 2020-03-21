package dev.vankka.basicsupport.configuration;

import ninja.leaping.configurate.objectmapping.Setting;
import ninja.leaping.configurate.objectmapping.serialize.ConfigSerializable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@ConfigSerializable
public class Configuration {

    @Setting(value = "BotToken")
    public String botToken = "";

    @Setting(value = "OpenChannelIds")
    public List<String> openChannelIds = new ArrayList<>(Collections.singletonList(""));

    @Setting(value = "TicketCategoryId")
    public String ticketCategoryId = "";

    @Setting(value = "TicketAdminRoleIds")
    public List<String> ticketAdminRoleIds = new ArrayList<>(Collections.singletonList(""));

    @Setting(value = "BinUrl")
    public String binUrl = "https://bin.example.com";

    @Setting(value = "TranscriptsChannelId")
    public String transcriptsChannelId = "";

    @Setting(value = "MaxTicketsPerUser")
    public int maxTicketsPerUser = 1;
}
