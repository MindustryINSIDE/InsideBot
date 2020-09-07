package insidebot;

import arc.struct.ObjectMap;
import arc.util.Log;
import arc.util.Strings;
import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.*;
import net.dv8tion.jda.api.entities.Message.Attachment;
import net.dv8tion.jda.api.events.guild.member.GuildMemberJoinEvent;
import net.dv8tion.jda.api.events.guild.member.GuildMemberLeaveEvent;
import net.dv8tion.jda.api.events.guild.member.update.GuildMemberUpdateNicknameEvent;
import net.dv8tion.jda.api.events.message.*;
import net.dv8tion.jda.api.hooks.ListenerAdapter;

import javax.annotation.Nonnull;
import java.awt.*;
import java.util.List;

import static insidebot.InsideBot.*;

public class Listener extends ListenerAdapter{

    public ObjectMap<Long, MetaInfo> messages = new ObjectMap<>();

    public Guild guild;
    public JDA jda;

    TextChannel channel;
    User lastUser;
    Message lastMessage;
    Message lastSentMessage;

    public Color normalColor = Color.decode("#C4F5B7");
    public Color errorColor = Color.decode("#ff3838");

    @Override
    public void onMessageReceived(@Nonnull MessageReceivedEvent event) {
        try{
            if (!event.getAuthor().isBot()) {
                commands.handle(event);
                handleEvent(event, EventType.messageReceive);

                UserInfo info = data.getUserInfo(event.getAuthor().getIdLong());
                info.setLastMessageId(event.getMessageIdLong());
                info.setName(event.getAuthor().getName());
            }
        }catch(Exception e){
            Log.err(e);
        }
    }

    @Override
    public void onMessageUpdate(@Nonnull MessageUpdateEvent event) {
        try{
            if (!event.getAuthor().isBot()) handleEvent(event, EventType.messageEdit);
        }catch(Exception e){
            Log.err(e);
        }
    }

    @Override
    public void onMessageDelete(@Nonnull MessageDeleteEvent event) {
        try{
            handleEvent(event, EventType.messageDelete);
        }catch(Exception e){
            Log.err(e);
        }
    }

    @Override
    public void onGuildMemberJoin(@Nonnull GuildMemberJoinEvent event) {
        try{
            if(!event.getUser().isBot()) handleEvent(event, EventType.userJoin);
        }catch(Exception e){
            Log.err(e);
        }
    }

    @Override
    public void onGuildMemberLeave(@Nonnull GuildMemberLeaveEvent event) {
        try{
            if(!event.getUser().isBot()) handleEvent(event, EventType.userLeave);
        }catch(Exception e){
            Log.err(e);
        }
    }

    @Override
    public void onGuildMemberUpdateNickname(@Nonnull GuildMemberUpdateNicknameEvent event) {
        try{
            if(!event.getUser().isBot()) handleEvent(event, EventType.userNameEdit);
        }catch(Exception e){
            Log.err(e);
        }
    }

    public void info(String text, Object... args){
        lastSentMessage = channel.sendMessage(Strings.format(text, args)).complete();
    }

    public void info(String title, String text){
        MessageEmbed object = new EmbedBuilder()
        .addField(title, text, true).setColor(normalColor).build();

        lastSentMessage = channel.sendMessage(object).complete();
    }

    public void err(String text, Object... args){
        err("Error", text, args);
    }

    public void err(String title, String text, Object... args){
        MessageEmbed e = new EmbedBuilder()
        .addField(title, Strings.format(text, args), true).setColor(errorColor).build();
        lastSentMessage = channel.sendMessage(e).complete();
    }

    public void log(MessageEmbed embed){
        jda.getTextChannelById(logChannelID).sendMessage(embed).queue();
    }

    public void log(MessageEmbed embed, List<Attachment> files){
        //jda.getTextChannelById(logChannelID).sendMessage(embed).addFile(files.forEach(a -> a.downloadToFile("../tmp/" + a.getFileName()).get())).queue();
    }

    public void handleAction(Object object, ActionType type){
        EmbedBuilder builder = new EmbedBuilder().setColor(listener.normalColor);
        User user = (User) object;
        switch (type) {
            case kick -> {
                guild.kick(user.getId()).queue();
            }case ban -> {
                guild.ban(user, 0).queue();
            }case unBan ->{
                guild.unban(user).queue();
            }case mute -> {
                guild.addRoleToMember(guild.getMember(user), jda.getRolesByName(muteRoleName, true).get(0)).queue();
            }case unMute -> {
                builder.addField(bundle.get("message.unmute"), bundle.format("message.unmute.text", user.getName()), true);
                builder.setFooter(data.zonedFormat());

                listener.log(builder.build());
                guild.removeRoleFromMember(guild.getMember(user), jda.getRolesByName(muteRoleName, true).get(0)).queue();
            }
        }
    }

    public void handleEvent(Object object, EventType type){
        EmbedBuilder embedBuilder = new EmbedBuilder().setColor(normalColor);
        embedBuilder.setFooter(data.zonedFormat());

        switch (type) {
            case messageReceive -> {
                MessageReceivedEvent event = (MessageReceivedEvent) object;
                MetaInfo info = new MetaInfo();
                info.text = event.getMessage().getContentRaw();
                info.id = event.getAuthor().getIdLong();

                if(!event.getMessage().getAttachments().isEmpty()){
                    info.file = event.getMessage().getAttachments();
                }

                messages.put(event.getMessageIdLong(), info);
            }case messageEdit -> {
                MessageUpdateEvent event = (MessageUpdateEvent) object;

                if(!messages.containsKey(event.getMessageIdLong())) return;

                MetaInfo info = messages.get(event.getMessageIdLong());


                if (!event.getMessage().isPinned()) {
                    embedBuilder.addField(bundle.get("message.edit"), bundle.format("message.edit.text",
                            event.getAuthor().getName(), event.getTextChannel().getAsMention()
                    ), true);
                    embedBuilder.addField(bundle.get("message.edit.old-content"), info.text, false);
                    embedBuilder.addField(bundle.get("message.edit.new-content"), event.getMessage().getContentRaw(), true);
                } else {
                    embedBuilder.addField(bundle.get("message.pin"), bundle.format("message.pin.text",
                            event.getAuthor().getName(), event.getTextChannel().getAsMention()
                    ), true);
                    embedBuilder.addField(bundle.get("message.pin.content"), lastMessage.getContentRaw(), false);
                }

                if (!event.getMessage().getAttachments().isEmpty()) {
                    info.file = event.getMessage().getAttachments();
                } else {
                    log(embedBuilder.build());
                }

                if (!event.getMessage().getContentRaw().isEmpty()) info.text = event.getMessage().getContentRaw();
            }case messageDelete -> {
                MessageDeleteEvent event = (MessageDeleteEvent) object;
                MetaInfo info = messages.get(event.getMessageIdLong());

                if(jda.retrieveUserById(info.id).complete() == null || info.text == null) return;

                embedBuilder.addField(bundle.get("message.delete"), bundle.format("message.delete.text",
                        jda.retrieveUserById(info.id).complete().getName(), event.getTextChannel().getAsMention()
                ),false);
                embedBuilder.addField(bundle.get("message.delete.content"), info.text, true);

                /*for(AuditLogEntry e : guild.retrieveAuditLogs().type(net.dv8tion.jda.api.audit.ActionType.MESSAGE_DELETE)){
                    e.getTargetIdLong();
                    e.getUser().getIdLong();
                }*/

                log(embedBuilder.build());
                messages.remove(event.getMessageIdLong());
            }case userJoin -> {
                GuildMemberJoinEvent event = (GuildMemberJoinEvent) object;

                embedBuilder.addField(bundle.get("message.user-join"), bundle.format("message.user-join.text", event.getUser().getName()),false);

                log(embedBuilder.build());
            }case userLeave -> {
                GuildMemberLeaveEvent event = (GuildMemberLeaveEvent) object;

                embedBuilder.addField(bundle.get("message.user-leave"), bundle.format("message.user-leave.text", event.getUser().getName()),false);

                log(embedBuilder.build());
            }case userNameEdit -> {
                GuildMemberUpdateNicknameEvent event = (GuildMemberUpdateNicknameEvent) object;

                embedBuilder.addField(bundle.get("message.username-changed"), bundle.format("message.username-changed.text",
                        event.getOldNickname(), event.getNewNickname()
                ),false);

                log(embedBuilder.build());
            }
        }
    }

    public enum ActionType{
        ban,
        unBan,
        kick,
        mute,
        unMute,
    }

    public enum EventType{
        messageEdit,
        messageDelete,
        messageReceive,

        userNameEdit,
        userJoin,
        userLeave,
    }

    private static class MetaInfo{
        public String text;
        public long id;
        public List<Attachment> file;
    }
}
