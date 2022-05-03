package org.carlspring.strongbox.users.service.impl;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import javax.inject.Inject;
import javax.inject.Qualifier;
import javax.transaction.Transactional;

import org.apache.commons.lang3.StringUtils;
import org.carlspring.strongbox.data.CacheName;
import org.carlspring.strongbox.domain.User;
import org.carlspring.strongbox.domain.UserEntity;
import org.carlspring.strongbox.repositories.UserRepository;
import org.carlspring.strongbox.users.domain.UserData;
import org.carlspring.strongbox.users.domain.Users;
import org.carlspring.strongbox.users.security.SecurityTokenProvider;
import org.carlspring.strongbox.users.service.UserService;
import org.carlspring.strongbox.users.service.impl.DatabaseUserService.Database;
import org.carlspring.strongbox.util.LocalDateTimeInstance;

import org.jose4j.lang.JoseException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Component;

/**
 * @author sbespalov
 */
@Component
@Database
@Transactional
public class DatabaseUserService implements UserService
{

    @Inject
    private SecurityTokenProvider tokenProvider;
    
    @Inject
    protected UserRepository userRepository;


    @Override
    @CacheEvict(cacheNames = CacheName.User.AUTHENTICATIONS, key = "#p0")
    public void deleteByUsername(String username)
    {
        userRepository.deleteById(username);
    }

    @Override
    public UserEntity findByUsername(String username)
    {
        return userRepository.findById(username).map(UserEntity.class::cast).orElse(null);
    }

    @Override
    public String generateSecurityToken(String username)
        throws JoseException
    {
        final User user = findByUsername(username);

        if (StringUtils.isEmpty(user.getSecurityTokenKey()))
        {
            return null;
        }

        final Map<String, String> claimMap = new HashMap<>();
        claimMap.put(UserData.SECURITY_TOKEN_KEY, user.getSecurityTokenKey());

        return tokenProvider.getToken(username, claimMap, null, null);
    }

    @Override
    @CacheEvict(cacheNames = CacheName.User.AUTHENTICATIONS, key = "#p0.username")
    public void updateAccountDetailsByUsername(User userToUpdate)
    {
        UserEntity user = findByUsername(userToUpdate.getUsername());
        if (user == null)
        {
            throw new UsernameNotFoundException(userToUpdate.getUsername());
        }

        if (!StringUtils.isBlank(userToUpdate.getPassword()))
        {
            user.setPassword(userToUpdate.getPassword());
        }

        if (StringUtils.isNotBlank(userToUpdate.getSecurityTokenKey()))
        {
            user.setSecurityTokenKey(userToUpdate.getSecurityTokenKey());
        }

        save(user);
    }

    @Override
    public Users getUsers()
    {
        Iterable<User> users = userRepository.findAll();
        return new Users(StreamSupport.stream(users.spliterator(), false).collect(Collectors.toSet()));
    }

    @Override
    public void revokeEveryone(String roleToRevoke)
    {
        List<User> resultList = userRepository.findUsersWithRole(roleToRevoke);

        resultList.forEach(user -> {
            user.getRoles().remove(roleToRevoke);
            save(user);
        });
    }

    @Override
    @CacheEvict(cacheNames = CacheName.User.AUTHENTICATIONS, key = "#p0.username")
    public User save(User user)
    {
        LocalDateTime now = LocalDateTimeInstance.now();

        UserEntity userEntity = Optional.ofNullable(findByUsername(user.getUsername())).orElseGet(() -> new UserEntity(user.getUsername()));

        if (!StringUtils.isBlank(user.getPassword()))
        {
            userEntity.setPassword(user.getPassword());
        }
        userEntity.setEnabled(user.isEnabled());
        userEntity.setRoles(user.getRoles());
        userEntity.setSecurityTokenKey(user.getSecurityTokenKey());
        userEntity.setLastUpdated(now);

        if (StringUtils.isNotBlank(user.getSourceId()) || StringUtils.isNotBlank(userEntity.getSourceId()))
        {
            throw new IllegalStateException("Can't modify external users.");
        }
        
        return userRepository.save(userEntity);
    }

    public void expireUser(String username, boolean clearSourceId)
    {
        UserEntity externalUserEntry = findByUsername(username);
        externalUserEntry.setLastUpdated(LocalDateTime.ofInstant(Instant.ofEpochMilli(0), ZoneId.systemDefault()));

        if (clearSourceId)
        {
            externalUserEntry.setSourceId("empty");
        }

        userRepository.save(externalUserEntry);
    }

    @Documented
    @Retention(RUNTIME)
    @Qualifier
    public @interface Database
    {
    }

}
