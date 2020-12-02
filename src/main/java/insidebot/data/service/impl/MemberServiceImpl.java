package insidebot.data.service.impl;

import arc.util.Log;
import discord4j.common.util.Snowflake;
import discord4j.core.object.entity.*;
import discord4j.rest.util.Permission;
import insidebot.common.services.DiscordService;
import insidebot.data.entity.*;
import insidebot.data.repository.LocalMemberRepository;
import insidebot.data.service.*;
import insidebot.data.service.AdminService.AdminActionType;
import insidebot.event.dispatcher.EventType.MemberUnmuteEvent;
import org.joda.time.*;
import org.slf4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.*;
import reactor.util.annotation.NonNull;

import java.util.Optional;
import java.util.function.Supplier;

@Service
public class MemberServiceImpl implements MemberService{
    @Autowired
    private DiscordService discordService;

    @Autowired
    private GuildService guildService;

    @Autowired
    private AdminService adminService;

    @Autowired
    private Logger log;

    @Autowired
    private LocalMemberRepository repository;

    @Override
    @Transactional(readOnly = true)
    public LocalMember get(Member member){
        return get(member.getGuildId(), member.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public LocalMember get(Guild guild, User user){
        return get(guild.getId(), user.getId());
    }

    @Override
    @Transactional(readOnly = true)
    public LocalMember get(Snowflake guildId, Snowflake userId){
        return repository.findByGuildIdAndId(guildId, userId);
    }

    @Override
    @Transactional
    public LocalMember getOr(Member member, Supplier<LocalMember> prov){
        return exists(member.getGuildId(), member.getId()) ? get(member) : prov.get();
    }

    @Override
    @Transactional
    public LocalMember getOr(Guild guild, User user, Supplier<LocalMember> prov){
        return exists(guild.getId(), user.getId()) ? get(guild, user) : prov.get();
    }

    @Override
    @Transactional
    public LocalMember getOr(Snowflake guildId, Snowflake userId, Supplier<LocalMember> prov){
        return exists(guildId, userId) ? get(guildId, userId) : prov.get();
    }

    @Override
    @Transactional
    public LocalMember save(LocalMember member){
        return repository.save(member);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean exists(Snowflake guildId, Snowflake userId){
        return get(guildId, userId) != null;
    }

    @Override
    @Transactional
    public void delete(LocalMember member){
        repository.delete(member);
    }

    @Override
    @Transactional
    public void deleteById(Snowflake userId){
        repository.deleteById(userId.asString());
    }

    @Scheduled(cron = "0 */2 * * * *")
    public void unmuteUsers(){
        Flux.fromIterable(repository.findAll())
            .filter(m -> !guildService.muteDisabled(m.guildId()))
            .filter(this::isMuteEnd)
            .subscribe(l -> {
                discordService.eventListener().publish(new MemberUnmuteEvent(discordService.gateway().getGuildById(l.guildId()).block(), l));
            }, Log::err);
    }

    @Scheduled(cron = "0 * * * * *")
    public void activeUsers(){
        Flux.fromIterable(repository.findAll())
            .filter(m -> !guildService.activeUserDisabled(m.guildId()))
            .filterWhen(l -> discordService.exists(l.guildId(), l.id()) ? Mono.just(true) : Mono.fromRunnable(() -> {
                log.warn("User '{}' not found. Deleting...", l.effectiveName());
                delete(l);
            }))
            .subscribe(l -> {
                Member member = discordService.gateway().getMemberById(l.guildId(), l.id()).block();
                if(member == null) return; // нереально
                Snowflake roleId = guildService.activeUserRoleId(member.getGuildId());
                if(l.isActiveUser()){
                    member.addRole(roleId).block();
                }else{
                    member.removeRole(roleId).block();
                    l.messageSeq(0);
                    save(l);
                }
        }, Log::err);
    }

    protected boolean isMuteEnd(@NonNull LocalMember member){
        AdminAction action = adminService.get(AdminActionType.mute, member.guildId(), member.id()).blockFirst();
        return action != null && action.isEnd();
    }

    @Override
    public String detailName(Member member){
        String name = member.getUsername();
        if(member.getNickname().isPresent()){
            name += String.format(" (%s)", member.getNickname().get());
        }
        return name;
    }
}
