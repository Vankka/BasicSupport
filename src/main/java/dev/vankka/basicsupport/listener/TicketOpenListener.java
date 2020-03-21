package dev.vankka.basicsupport.listener;

import dev.vankka.basicsupport.BasicSupport;
import dev.vankka.basicsupport.object.Emoji;
import dev.vankka.basicsupport.model.Ticket;
import lombok.RequiredArgsConstructor;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.MessageBuilder;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Category;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.MessageType;
import net.dv8tion.jda.api.events.message.guild.GuildMessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import javax.annotation.Nonnull;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

@RequiredArgsConstructor
public class TicketOpenListener extends ListenerAdapter {

    private final BasicSupport bot;

    @Override
    public void onGuildMessageReceived(@Nonnull GuildMessageReceivedEvent event) {
        if (event.getMessage().getType() == MessageType.CHANNEL_PINNED_ADD && bot.getTickets().stream()
                .anyMatch(ticket -> ticket.getChannelId().equals(event.getChannel().getId()))) {
            // initial pin message
            event.getMessage().delete().queue();
            return;
        }

        Member member = event.getMember();
        if (event.getAuthor().isBot() || event.getMessage().getType() != MessageType.DEFAULT || member == null) {
            return;
        }

        if ((bot.isTicketAdmin(member) || member.hasPermission(Permission.ADMINISTRATOR))
                && !event.getMessage().getContentRaw().toLowerCase().trim().startsWith("!create")) {
            // don't create tickets for ticket or server admins, unless they specifically want to using !create
            return;
        }

        int max = bot.getConfiguration().maxTicketsPerUser;
        if (bot.getTickets().stream().filter(ticket -> ticket.getOwnerId().equals(event.getAuthor().getId())).count() >= max) {
            event.getChannel().sendMessage(member.getAsMention() + ", You can only have " + max + " tickets at once")
                    .queue(msg -> msg.delete().queueAfter(5, TimeUnit.SECONDS));
            return;
        }

        if (bot.getConfiguration().openChannelIds.stream().anyMatch(id -> id.equals(event.getChannel().getId()))) {
            event.getMessage().delete().queue();

            Category category = event.getJDA().getCategoryById(bot.getConfiguration().ticketCategoryId);
            if (category == null) {
                event.getChannel().sendMessage(member.getAsMention() + ", Failed to create a ticket (no category defined). Please try again later!")
                        .queue(message -> message.delete().queueAfter(5, TimeUnit.SECONDS));
                return;
            }

            String message = event.getMessage().getContentRaw();
            category.createTextChannel(String.valueOf(bot.getTicketCount().incrementAndGet()))
                    .addPermissionOverride(member, Arrays.asList(Permission.MESSAGE_WRITE, Permission.MESSAGE_READ), Collections.emptyList())
                    .queue(channel -> {
                        bot.getTickets().add(new Ticket(channel.getId(), member.getId(), message, new CopyOnWriteArrayList<>()));
                        MessageEmbed embed = new EmbedBuilder()
                                .setAuthor(member.getEffectiveName(), null, event.getAuthor().getEffectiveAvatarUrl())
                                .setDescription(message)
                                .setFooter("Click the " + Emoji.WHITE_CHECK_MARK + " to close this ticket.\n" +
                                        "Click the " + Emoji.FLOPPY_DISK + " to get the transcript for this ticket\n" +
                                        "Add or remove users with " + Emoji.HEAVY_PLUS_SIGN + " / " + Emoji.HEAVY_MINUS_SIGN)
                                .build();

                        channel.sendMessage(new MessageBuilder().setContent(member.getAsMention()).setEmbed(embed).build()).queue(msg -> {
                            msg.pin().queue();
                            msg.addReaction(Emoji.WHITE_CHECK_MARK).queue();
                            msg.addReaction(Emoji.HEAVY_PLUS_SIGN).queue();
                            msg.addReaction(Emoji.HEAVY_MINUS_SIGN).queue();
                            msg.addReaction(Emoji.FLOPPY_DISK).queue();
                        });
                        event.getChannel().sendMessage(member.getEffectiveName() + ", Your ticket has been created! " + channel.getAsMention())
                                .queue(msg -> msg.delete().queueAfter(5, TimeUnit.SECONDS));

                    });
        }
    }
}
