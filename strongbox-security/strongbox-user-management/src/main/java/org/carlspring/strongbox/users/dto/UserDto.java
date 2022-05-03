package org.carlspring.strongbox.users.dto;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import java.util.stream.Collectors;
import org.carlspring.strongbox.domain.User;
import org.carlspring.strongbox.domain.SecurityRole;
import org.carlspring.strongbox.domain.SecurityRoleEntity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * @author mtodorov
 */
public class UserDto
        implements Serializable, User
{

    private String username;

    private String password;

    private Boolean enabled = true;

    private Set<String> roles = new HashSet<>();

    private String securityTokenKey;

    private LocalDateTime lastUpdate;

    private String sourceId;

    @Override
    public String getUuid()
    {
        return getUsername();
    }

    @Override
    public String getUsername()
    {
        return username;
    }

    public void setUsername(String username)
    {
        this.username = username;
    }

    @Override
    public String getPassword()
    {
        return password;
    }

    public void setPassword(String password)
    {
        this.password = password;
    }

    @Override
    @JsonIgnore
    public Set<SecurityRole> getRoles()
    {
        return roles != null ? roles.stream()
                                    .map(role -> new SecurityRoleEntity(role))
                                    .collect(Collectors.toSet())
                             : new HashSet<>();
    }

    public void setRoles(Set<SecurityRole> roles)
    {
        if (roles == null)
        {
            this.roles = new HashSet<>();
            return;
        }
        this.roles = roles.stream().map(SecurityRole::getRoleName).collect(Collectors.toSet());
    }

    public void setRoleNames(Set<String> roles)
    {
        this.roles = roles != null ? new HashSet<>(roles) : new HashSet<>();
    }

    @JsonProperty("roles")
    public Set<String> getRoleNames()
    {
        return Collections.unmodifiableSet(roles);
    }

    
    public void addRole(String role)
    {
        roles.add(role);
    }

    public void removeRole(String role)
    {
        removeRole(new SecurityRoleEntity(role));
    }

    public void removeRole(SecurityRole role)
    {
        roles.remove(role);
    }

    public boolean hasRole(SecurityRole role)
    {
        return roles.contains(role);
    }

    @Override
    public String getSecurityTokenKey()
    {
        return securityTokenKey;
    }

    public void setSecurityTokenKey(String securityTokenKey)
    {
        this.securityTokenKey = securityTokenKey;
    }

    @Override
    public Boolean isEnabled()
    {
        return enabled;
    }

    public void setEnabled(final Boolean enabled)
    {
        this.enabled = enabled;
    }

    @Override
    @JsonIgnore
    public LocalDateTime getLastUpdated()
    {
        return lastUpdate;
    }

    public void setLastUpdate(LocalDateTime lastUpdate)
    {
        this.lastUpdate = lastUpdate;
    }

    @Override
    @JsonIgnore
    public String getSourceId()
    {
        return sourceId;
    }

    public void setSourceId(String sourceId)
    {
        this.sourceId = sourceId;
    }

    @Override
    public String toString()
    {
        final StringBuilder sb = new StringBuilder("User{");
        sb.append("username='")
          .append(username)
          .append('\'');
        sb.append(", roles=")
          .append(roles);
        sb.append('}');
        return sb.toString();
    }
}
