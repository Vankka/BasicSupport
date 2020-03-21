package dev.vankka.basicsupport;

import com.google.common.reflect.TypeToken;
import com.jagrosh.jdautilities.commons.waiter.EventWaiter;
import dev.vankka.basicsupport.configuration.Configuration;
import dev.vankka.basicsupport.listener.TicketActionsListener;
import dev.vankka.basicsupport.listener.TicketOpenListener;
import dev.vankka.basicsupport.model.Ticket;
import dev.vankka.basicsupport.object.Emoji;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.utils.MemberCachePolicy;
import net.dv8tion.jda.api.utils.cache.CacheFlag;
import ninja.leaping.configurate.ConfigurationNode;
import ninja.leaping.configurate.ConfigurationOptions;
import ninja.leaping.configurate.commented.CommentedConfigurationNode;
import ninja.leaping.configurate.hocon.HoconConfigurationLoader;
import ninja.leaping.configurate.loader.ConfigurationLoader;
import ninja.leaping.configurate.objectmapping.ObjectMappingException;
import org.apache.commons.lang3.StringUtils;

import javax.security.auth.login.LoginException;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

public class BasicSupport {

    public static void main(String[] args) throws Exception {
        new BasicSupport();
    }

    private final List<Ticket> tickets = new CopyOnWriteArrayList<>();
    private final List<String> dmsToggled = new CopyOnWriteArrayList<>();
    private final AtomicLong ticketCount = new AtomicLong(0);
    private final EventWaiter eventWaiter = new EventWaiter();
    private Connection connection;
    private Configuration configuration;

    public BasicSupport() throws SQLException, ClassNotFoundException, IOException, ObjectMappingException, LoginException, InterruptedException {
        reloadConfiguration();

        String botToken = configuration.botToken;
        if (StringUtils.isBlank(botToken)) {
            System.out.println("+--------------------+");
            System.out.println("| Bot token is blank |");
            System.out.println("+--------------------+");
            System.exit(1);
            return;
        }

        JDA jda = JDABuilder.create(botToken, GatewayIntent.GUILD_MESSAGES, GatewayIntent.GUILD_MESSAGE_REACTIONS, GatewayIntent.GUILD_MEMBERS)
                .addEventListeners(new TicketOpenListener(this), new TicketActionsListener(this), eventWaiter)
                .setMemberCachePolicy(MemberCachePolicy.ALL)
                .setEnabledCacheFlags(EnumSet.of(CacheFlag.MEMBER_OVERRIDES))
                .build();

        Class.forName("org.h2.Driver"); // ensure the driver manager can get the driver
        connection = DriverManager.getConnection("jdbc:h2:./database.db");

        connection.createStatement().execute("CREATE TABLE IF NOT EXISTS " +
                "basicsupport_tickets (channelId VARCHAR UNIQUE, ownerId VARCHAR, extraUsers VARCHAR, message VARCHAR, extraDms VARCHAR)");
        connection.createStatement().execute("CREATE TABLE IF NOT EXISTS " +
                "basicsupport_ticketdata (ticketCount LONG, dmsToggled VARCHAR)");

        try (ResultSet resultSet = connection.createStatement().executeQuery("SELECT * FROM basicsupport_tickets")) {
            while (resultSet.next()) {
                String extraDms = resultSet.getString("extraDms");

                List<String> extraDmList = new CopyOnWriteArrayList<>();
                if (extraDms.contains(",")) {
                    Collections.addAll(extraDmList, extraDms.split(","));
                } else if (!extraDms.isEmpty()) {
                    extraDmList.add(extraDms);
                }

                tickets.add(new Ticket(
                        resultSet.getString("channelId"),
                        resultSet.getString("ownerId"),
                        resultSet.getString("message"),
                        extraDmList
                ));
            }
        }
        try (ResultSet resultSet = connection.createStatement().executeQuery("SELECT * FROM basicsupport_ticketdata")) {
            if (resultSet.next()) {
                ticketCount.set(resultSet.getLong("ticketCount"));
                String dmsToggled = resultSet.getString("dmsToggled");
                if (dmsToggled.contains(",")) {
                    Collections.addAll(this.dmsToggled, dmsToggled.split(","));
                } else if (!dmsToggled.isEmpty()) {
                    this.dmsToggled.add(dmsToggled);
                }
            }
        }

        jda.awaitReady();
        for (String openChannelId : configuration.openChannelIds) {
            TextChannel textChannel = jda.getTextChannelById(openChannelId);
            if (textChannel == null) {
                continue;
            }
            for (Message message : textChannel.getHistoryAfter(0L, 1).complete().getRetrievedHistory()) {
                message.addReaction(Emoji.FLOPPY_DISK).queue();
            }
        }

        new Timer("BasicSupport - DB Save").schedule(new TimerTask() {
            @Override
            public void run() {
                save();
            }
        }, 1000L, TimeUnit.MINUTES.toMillis(1));

        Runtime.getRuntime().addShutdownHook(new Thread(this::stop));
    }

    private void save() {
        try {
            connection.createStatement().execute("DELETE FROM basicsupport_tickets");
            for (Ticket ticket : tickets) {
                connection.createStatement().execute("INSERT INTO basicsupport_tickets(channelId, ownerId, message, extraDms) VALUES " +
                        "('" + ticket.getChannelId() + "', '" + ticket.getOwnerId() + "', " +
                        "'" + ticket.getMessage() + "', '" + String.join(",", ticket.getExtraDms()) + "')");
            }
            connection.createStatement().execute("DELETE FROM basicsupport_ticketdata");
            connection.createStatement().execute("INSERT INTO basicsupport_ticketdata (ticketCount, dmsToggled) " +
                    "VALUES ('" + ticketCount.get() + "', '" + String.join(",", dmsToggled) + "')");
        } catch (SQLException e) {
            System.err.println("Failed to save to DB");
            e.printStackTrace();
        }
    }

    @SuppressWarnings("UnstableApiUsage")
    public void reloadConfiguration() throws IOException, ObjectMappingException {
        File configurationFile = new File("config.conf");
        if (!configurationFile.exists()) {
            Files.createFile(configurationFile.toPath());
        }

        ConfigurationLoader<CommentedConfigurationNode> configurationLoader =
                HoconConfigurationLoader.builder().setFile(configurationFile).build();
        ConfigurationNode configurationNode =
                configurationLoader.load(ConfigurationOptions.defaults().setShouldCopyDefaults(true));

        Configuration configuration = configurationNode.getValue(TypeToken.of(Configuration.class));
        if (configuration == null) {
            configuration = new Configuration();
        }
        configurationNode = configurationNode.setValue(TypeToken.of(Configuration.class), configuration);

        configurationLoader.save(configurationNode);
        this.configuration = configuration;
    }

    public boolean isTicketAdmin(Member member) {
        return member.getRoles().stream().anyMatch(role -> configuration.ticketAdminRoleIds.contains(role.getId()));
    }

    public void stop() {
        try {
            connection.close();
        } catch (SQLException e) {
            System.err.println("Failed to close database connection");
            e.printStackTrace();
        }
    }

    public EventWaiter getEventWaiter() {
        return eventWaiter;
    }

    public List<Ticket> getTickets() {
        return tickets;
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public AtomicLong getTicketCount() {
        return ticketCount;
    }

    public List<String> getDmsToggled() {
        return dmsToggled;
    }
}
