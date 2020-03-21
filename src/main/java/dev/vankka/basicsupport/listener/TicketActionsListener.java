package dev.vankka.basicsupport.listener;

import com.google.common.collect.Lists;
import dev.vankka.basicsupport.BasicSupport;
import dev.vankka.basicsupport.object.Emoji;
import dev.vankka.basicsupport.model.Ticket;
import dev.vankka.basicsupport.util.BinUtil;
import lombok.RequiredArgsConstructor;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.events.message.guild.react.GuildMessageReactionAddEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import javax.annotation.Nonnull;
import java.util.*;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@RequiredArgsConstructor
public class TicketActionsListener extends ListenerAdapter {

    private final BasicSupport bot;
    private final Pattern mentionPattern = Pattern.compile("<@([0-9]{16,20})>");

    @Override
    public void onGuildMessageReactionAdd(@Nonnull GuildMessageReactionAddEvent event) {
        Member member = event.getMember();
        if (event.getUser().isBot()) {
            return;
        }

        String name = event.getReactionEmote().getName();
        if (bot.getConfiguration().openChannelIds.stream().anyMatch(id -> event.getChannel().getId().equals(id)) && name.equals(Emoji.FLOPPY_DISK)) {
            event.getReaction().removeReaction(event.getUser()).queue();
            if (bot.getDmsToggled().contains(event.getUserId())) {
                bot.getDmsToggled().remove(event.getUserId());
                event.getChannel().sendMessage(member.getAsMention() + ", You will receive transcripts again")
                        .queue(msg -> msg.delete().queueAfter(5, TimeUnit.SECONDS));
            } else {
                bot.getDmsToggled().add(event.getUserId());
                event.getChannel().sendMessage(member.getAsMention() + ", You will no longer receive transcripts")
                        .queue(msg -> msg.delete().queueAfter(5, TimeUnit.SECONDS));
            }
            return;
        }

        Ticket ticket = bot.getTickets().stream()
                .filter(t -> t.getChannelId().equals(event.getChannel().getId()))
                .findAny().orElse(null);
        if (ticket == null) {
            return;
        }

        if (event.getChannel().getHistoryAfter(0L, 1).complete()
                .getRetrievedHistory().stream().noneMatch(msg -> msg.getId().equals(event.getMessageId()))) {
            return;
        }

        event.getReaction().removeReaction(event.getUser()).queue();

        boolean canDoAnything = member.isOwner() || member.hasPermission(Permission.ADMINISTRATOR)
                || member.getId().equals(ticket.getOwnerId()) || bot.isTicketAdmin(member);

        if (name.equals(Emoji.FLOPPY_DISK)) {
            if (ticket.getOwnerId().equals(event.getUserId())) {
                event.getChannel().sendMessage(member.getAsMention() + ", you are already going to receive the transcript for this ticket, and cannot unsubscribe because you created the ticket")
                        .queue(msg -> msg.delete().queueAfter(5, TimeUnit.SECONDS));
                return;
            }
            if (ticket.getExtraDms().contains(event.getUserId())) {
                ticket.getExtraDms().remove(event.getUserId());
                event.getChannel().sendMessage(member.getAsMention() + ", you will no longer receive the transcript for this ticket")
                        .queue(msg -> msg.delete().queueAfter(5, TimeUnit.SECONDS));
            } else {
                ticket.getExtraDms().add(event.getUserId());
                event.getChannel().sendMessage(member.getAsMention() + ", you will receive the transcript for this ticket")
                        .queue(msg -> msg.delete().queueAfter(5, TimeUnit.SECONDS));
            }
            return;
        }

        if (!canDoAnything) {
            return;
        }

        switch (name) {
            case Emoji.WHITE_CHECK_MARK:
                bot.getTickets().remove(ticket);
                event.getChannel().sendMessage("Ticket closing, requested by " + member.getAsMention()).queue();

                List<Message> messages = new ArrayList<>();
                MessageHistory messageHistory = event.getChannel().getHistory();

                List<Message> currentMessages;
                while (!(currentMessages = messageHistory.retrievePast(50).complete()).isEmpty()) {
                    messages.addAll(currentMessages);
                }

                Set<String> participants = new HashSet<>();
                StringBuilder stringBuilder = new StringBuilder();

                Member authorMember = event.getGuild().getMemberById(ticket.getOwnerId());
                User authorUser = event.getJDA().getUserById(ticket.getOwnerId());
                String authorName = authorMember != null ? authorMember.getEffectiveName()
                        : authorUser != null ? authorUser.getAsTag()
                        : "<Could not be retrieved> (" + ticket.getOwnerId() + ")";

                Set<User> dmUsers = new HashSet<>();
                if (authorUser != null) {
                    dmUsers.add(authorUser);
                }

                for (Message message : Lists.reverse(messages)) {
                    Member messageMember = message.getMember();
                    User user = message.getAuthor();

                    participants.add(user.toString());
                    if (dmUsers.stream().noneMatch(u -> u.getId().equals(user.getId()))) {
                        dmUsers.add(user);
                    }

                    String userName = messageMember != null ? messageMember.getEffectiveName() : user.getAsTag();
                    if (user.getId().equals(ticket.getOwnerId())) {
                        authorName = userName;
                        authorUser = user;
                    }

                    if (user.isBot()) {
                        userName += " [BOT]";
                    } else if (user.getId().equals(ticket.getOwnerId())) {
                        userName += " [AUTHOR]";
                    }

                    String content = message.getContentRaw();
                    for (TextChannel mentionedChannel : message.getMentionedChannels()) {
                        content = content.replace("<#" + mentionedChannel.getId() + ">", "#" + mentionedChannel.getName());
                    }
                    for (Role mentionedRole : message.getMentionedRoles()) {
                        content = content.replace("<&" + mentionedRole.getId() + ">", "@" + mentionedRole.getName());
                    }
                    for (Member mentionedMember : message.getMentionedMembers()) {
                        content = content.replaceAll("<@!?" + mentionedMember.getId() + ">", "@" + mentionedMember.getEffectiveName());
                    }
                    for (User mentionedUser : message.getMentionedUsers()) {
                        content = content.replaceAll("<@!?" + mentionedUser.getId() + ">", "@" + mentionedUser.getName());
                    }
                    for (Emote emote : message.getEmotes()) {
                        content = content.replaceAll("<a?:" + emote.getName() + ":" + emote.getId() + ">", ":" + emote.getName() + ":");
                    }

                    stringBuilder.append(userName)
                            .append(": ").append(content).append(" ")
                            .append(message.getAttachments().stream().map(Message.Attachment::getUrl)
                                    .collect(Collectors.joining(" "))).append("\n");
                }

                String transcript = BinUtil.createReport(authorName + " (" + ticket.getOwnerId() + ")",
                        participants, stringBuilder.toString(), bot.getConfiguration().binUrl);

                for (String s : bot.getDmsToggled()) {
                    if (!s.equals(ticket.getOwnerId())) {
                        dmUsers.removeIf(u -> u.getId().equals(s));
                    }
                }

                for (String extraDm : ticket.getExtraDms()) {
                    User user = event.getJDA().getUserById(extraDm);
                    if (user != null && dmUsers.stream().noneMatch(u -> u.getId().equals(user.getId()))) {
                        dmUsers.add(user);
                    }
                }

                for (User dmUser : dmUsers) {
                    if (dmUser.isBot()) {
                        continue;
                    }

                    dmUser.openPrivateChannel().queue(privateChannel ->
                                    privateChannel.sendMessage(
                                            new EmbedBuilder()
                                                    .setTitle("Ticket " + event.getChannel().getName() + " closed in " + event.getGuild().getName())
                                                    .setThumbnail(event.getGuild().getIconUrl())
                                                    .addField("Transcript", transcript, false)
                                                    .build()
                                    ).queue(message -> {}, throwable -> {})
                            , throwable -> {});
                }

                TextChannel textChannel = event.getJDA().getTextChannelById(bot.getConfiguration().transcriptsChannelId);
                if (textChannel != null) {
                    textChannel.sendMessage(
                            new EmbedBuilder()
                                    .setThumbnail(authorUser != null ? authorUser.getEffectiveAvatarUrl() : null)
                                    .setTitle("Ticket " + event.getChannel().getName() + " was closed")
                                    .setDescription(ticket.getMessage())
                                    .addField("Author", authorName + " (" + ticket.getOwnerId() + ")", true)
                                    .addField("Closed by", member.getEffectiveName() + " (" + event.getUserId() + ")", true)
                                    .addField("Transcript", transcript, false)
                                    .build()
                    ).queue();
                }
                event.getChannel().delete().queue();

                break;
            case Emoji.HEAVY_PLUS_SIGN:
                event.getChannel().sendMessage(member.getAsMention() + ", Please choose a user to add to this ticket, you can use `Username#1234`, or the User's ID").queue(message ->
                        bot.getEventWaiter().waitForEvent(GuildMessageReceivedEvent.class, e -> e.getAuthor().getId().equals(event.getUserId()), e -> {
                            e.getMessage().delete().queue();
                            ScheduledFuture<?> scheduledFuture = message.delete().queueAfter(10, TimeUnit.SECONDS);

                            User user = null;
                            try {
                                user = event.getJDA().getUserByTag(e.getMessage().getContentRaw());
                                if (user == null) {
                                    user = event.getJDA().getUserById(e.getMessage().getContentRaw());
                                }
                            } catch (Throwable ignored) {}

                            if (user == null) {
                                message.editMessage(member.getAsMention() + ", Couldn't find that user").queue();
                                return;
                            }

                            Member mem = event.getGuild().getMember(user);
                            if (mem == null) {
                                message.editMessage(member.getAsMention() + ", That user isn't in this server").queue();
                                return;
                            }

                            if (event.getChannel().getPermissionOverride(mem) != null) {
                                message.editMessage(member.getAsMention() + ", That user is already in this ticket").queue();
                                return;
                            }

                            event.getChannel().getManager().putPermissionOverride(mem, Arrays.asList(Permission.MESSAGE_READ, Permission.MESSAGE_WRITE), Collections.emptyList()).queue(v -> {});
                            if (scheduledFuture.cancel(false)) {
                                message.delete().queue();
                            }
                            event.getChannel().sendMessage(mem.getAsMention() + " was added to this ticket by " + member.getAsMention()).queue();
                        }, 30, TimeUnit.SECONDS, () -> message.editMessage(member.getAsMention() + ", Timed out").queue(msg -> msg.delete().queueAfter(10, TimeUnit.SECONDS))));
                break;
            case Emoji.HEAVY_MINUS_SIGN:
                event.getChannel().sendMessage(member.getAsMention() + ", Please choose a user to remove from this ticket, you can use `Username#1234`, the User's ID or you can mention the user").queue(message ->
                        bot.getEventWaiter().waitForEvent(GuildMessageReceivedEvent.class, e -> e.getAuthor().getId().equals(event.getUserId()), e -> {
                            e.getMessage().delete().queue();
                            ScheduledFuture<?> scheduledFuture = message.delete().queueAfter(10, TimeUnit.SECONDS);

                            User user = null;
                            try {
                                user = event.getJDA().getUserByTag(e.getMessage().getContentRaw());
                                if (user == null) {
                                    user = event.getJDA().getUserById(e.getMessage().getContentRaw());
                                }
                                if (user == null) {
                                    Matcher matcher = mentionPattern.matcher(e.getMessage().getContentRaw());
                                    if (matcher.matches()) {
                                        user = event.getJDA().getUserById(matcher.group(1));
                                    }
                                }
                            } catch (Throwable ignored) {}

                            if (user == null) {
                                message.editMessage(member.getAsMention() + ", Couldn't find that user").queue();
                                return;
                            }

                            if (user.getId().equals(ticket.getOwnerId())) {
                                message.editMessage(member.getAsMention() + ", Cannot remove the ticket owner from this ticket").queue();
                                return;
                            }

                            Member mem = event.getGuild().getMember(user);
                            if (mem == null) {
                                message.editMessage(member.getAsMention() + ", That user isn't in this server").queue();
                                return;
                            }

                            if (event.getChannel().getPermissionOverride(mem) == null) {
                                message.editMessage(member.getAsMention() + ", That user is already in this ticket").queue();
                                return;
                            }

                            event.getChannel().getManager().removePermissionOverride(mem).queue();
                            if (scheduledFuture.cancel(false)) {
                                message.delete().queue();
                            }
                            event.getChannel().sendMessage(mem.getAsMention() + " was removed from this ticket by " + event.getMember().getAsMention()).queue();
                        }, 30, TimeUnit.SECONDS, () -> message.editMessage(member.getAsMention() + ", Timed out").queue(msg -> msg.delete().queueAfter(10, TimeUnit.SECONDS))));
                break;
        }
    }
}
