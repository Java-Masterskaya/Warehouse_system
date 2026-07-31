package com.warehouse.security.service;

import com.warehouse.exception.ActiveTokenLimitExceededException;
import com.warehouse.exception.InvalidTokenException;
import com.warehouse.exception.TokenReuseException;
import com.warehouse.security.util.JwtUtil;
import com.warehouse.security.model.TokenPair;
import com.warehouse.security.util.TokenHashUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

@Slf4j
@Service
@RequiredArgsConstructor
public class TokenServiceImpl implements TokenService {

    private final JwtUtil jwtUtil;
    private final RedisTemplate<String, String> redisTemplate;

    private static final String REFRESH_PREFIX = "refresh:";
    private static final String BLACKLIST_PREFIX = "blacklist:";
    private static final String USER_TOKENS_PREFIX = "user:tokens:";
    private static final String REFRESH_ROTATION_PREFIX = "rotation:";
    private static final String USER_ACCESS_PREFIX = "user:access:";

    private static final String USER_REFRESH_SET_PREFIX = "user:refresh:set:";
    private static final String USER_ACCESS_SET_PREFIX = "user:access:set:";

    private static final String REFRESH_RETRY_PREFIX = "refresh:retry:";
    private static final String REFRESH_RETRY_OWNER_PREFIX = "refresh:retry:owner:";
    private static final String USER_REFRESH_RETRY_SET_PREFIX = "user:refresh:retry:set:";
    private static final long ROTATION_COMPLETED = 1L;
    private static final long TOKEN_LIMIT_EXCEEDED = -3L;
    private static final long TOKEN_PAIR_MISMATCH = -4L;
    private static final long DEFAULT_MAX_ACTIVE_TOKEN_PAIRS_PER_USER = 100L;
    private static final DefaultRedisScript<Long> ROTATE_REFRESH_TOKEN_SCRIPT = new DefaultRedisScript<>(
            """
                    local function extendTtl(key, requestedTtl)
                        local currentTtl = redis.call('pttl', key)
                        if currentTtl < requestedTtl then
                            redis.call('pexpire', key, requestedTtl)
                        end
                    end

                    local rotationTtl = tonumber(ARGV[2])
                    local retryTtl = tonumber(ARGV[4])
                    local newRefreshTtl = tonumber(ARGV[8])
                    local newAccessTtl = tonumber(ARGV[9])
                    if not rotationTtl or rotationTtl <= 0
                            or not retryTtl or retryTtl <= 0
                            or not newRefreshTtl or newRefreshTtl <= 0
                            or not newAccessTtl or newAccessTtl <= 0 then
                        return -2
                    end
                    local oldRefreshType = redis.call('type', KEYS[1]).ok
                    if oldRefreshType == 'none' then
                        return 0
                    end
                    if oldRefreshType ~= 'string' then
                        return -2
                    end

                    local refreshSetType = redis.call('type', KEYS[5]).ok
                    if refreshSetType ~= 'none' and refreshSetType ~= 'set' then
                        return -2
                    end

                    local accessSetType = redis.call('type', KEYS[9]).ok
                    if accessSetType ~= 'none' and accessSetType ~= 'set' then
                        return -2
                    end

                    local retrySetType = redis.call('type', KEYS[10]).ok
                    if retrySetType ~= 'none' and retrySetType ~= 'set' then
                        return -2
                    end

                    local oldRetryOwnerType = redis.call('type', KEYS[12]).ok
                    if oldRetryOwnerType ~= 'none' and oldRetryOwnerType ~= 'string' then
                        return -2
                    end

                    if redis.call('exists', KEYS[2]) == 1 or redis.call('exists', KEYS[3]) == 1 then
                        return 0
                    end

                    if redis.call('exists', KEYS[6]) == 1
                            or redis.call('exists', KEYS[7]) == 1
                            or redis.call('exists', KEYS[8]) == 1
                            or redis.call('exists', KEYS[11]) == 1 then
                        return -2
                    end

                    local storedUserId = redis.call('get', KEYS[1])
                    if storedUserId ~= ARGV[1] then
                        return 0
                    end

                    local oldPairType = redis.call('type', KEYS[4]).ok
                    if oldPairType ~= 'string' then
                        return -2
                    end
                    local oldAccessHash = redis.call('get', KEYS[4])
                    if not oldAccessHash or oldAccessHash == '' then
                        return -2
                    end
                    local oldAccessKey = nil
                    -- Legacy pairs stored only an "active" marker, so no access hash can be selected safely.
                    if oldAccessHash ~= 'active' then
                        oldAccessKey = 'user:access:' .. ARGV[1] .. ':' .. oldAccessHash
                        local oldAccessType = redis.call('type', oldAccessKey).ok
                        if oldAccessType ~= 'none' and oldAccessType ~= 'string' then
                            return -2
                        end
                    end

                    if retrySetType == 'set' then
                        for _, retryHash in ipairs(redis.call('smembers', KEYS[10])) do
                            if redis.call('exists', 'refresh:retry:' .. retryHash) == 0 then
                                redis.call('srem', KEYS[10], retryHash)
                            end
                        end
                    end

                    local predecessorRefreshHash = redis.call('get', KEYS[12])
                    if predecessorRefreshHash then
                        redis.call('del', 'refresh:retry:' .. predecessorRefreshHash)
                        redis.call('srem', KEYS[10], predecessorRefreshHash)
                    end
                    redis.call('del', KEYS[12])

                    redis.call('del', KEYS[1])
                    redis.call(
                            'psetex',
                            KEYS[2],
                            ARGV[2],
                            ARGV[1] .. '|' .. ARGV[6] .. '|' .. oldAccessHash
                    )
                    redis.call('psetex', KEYS[3], ARGV[4], ARGV[3])
                    redis.call('psetex', KEYS[11], ARGV[4], ARGV[5])
                    redis.call('sadd', KEYS[10], ARGV[5])
                    extendTtl(KEYS[10], retryTtl)
                    redis.call('del', KEYS[4])
                    if refreshSetType == 'set' then
                        redis.call('srem', KEYS[5], ARGV[5])
                    end

                    redis.call('psetex', KEYS[6], ARGV[8], ARGV[1])
                    redis.call('psetex', KEYS[7], ARGV[8], ARGV[7])
                    redis.call('sadd', KEYS[5], ARGV[6])
                    extendTtl(KEYS[5], newRefreshTtl)

                    if oldAccessKey then
                        local remainingAccessTtl = redis.call('pttl', oldAccessKey)
                        local blacklistKey = 'blacklist:' .. oldAccessHash
                        local currentBlacklistTtl = redis.call('pttl', blacklistKey)
                        if currentBlacklistTtl > remainingAccessTtl then
                            remainingAccessTtl = currentBlacklistTtl
                        end
                        if remainingAccessTtl > 0 then
                            redis.call('psetex', blacklistKey, remainingAccessTtl, 'blacklisted')
                        end
                        redis.call('del', oldAccessKey)
                        redis.call('srem', KEYS[9], oldAccessHash)
                    end

                    redis.call('psetex', KEYS[8], ARGV[9], 'active')
                    redis.call('sadd', KEYS[9], ARGV[7])
                    extendTtl(KEYS[9], newAccessTtl)
                    return 1
                    """,
            Long.class
    );
    private static final DefaultRedisScript<Long> STORE_TOKEN_PAIR_SCRIPT = new DefaultRedisScript<>(
            """
                    local function extendTtl(key, requestedTtl)
                        local currentTtl = redis.call('pttl', key)
                        if currentTtl < requestedTtl then
                            redis.call('pexpire', key, requestedTtl)
                        end
                    end

                    local refreshTtl = tonumber(ARGV[4])
                    local accessTtl = tonumber(ARGV[5])
                    local maxActivePairs = tonumber(ARGV[6])
                    if not refreshTtl or refreshTtl <= 0
                            or not accessTtl or accessTtl <= 0
                            or not maxActivePairs or maxActivePairs <= 0 then
                        return -2
                    end

                    local refreshSetType = redis.call('type', KEYS[3]).ok
                    local accessSetType = redis.call('type', KEYS[5]).ok
                    if refreshSetType ~= 'none' and refreshSetType ~= 'set' then
                        return -2
                    end
                    if accessSetType ~= 'none' and accessSetType ~= 'set' then
                        return -2
                    end
                    if redis.call('exists', KEYS[1]) == 1
                            or redis.call('exists', KEYS[2]) == 1
                            or redis.call('exists', KEYS[4]) == 1 then
                        return -2
                    end

                    if refreshSetType == 'set' then
                        for _, refreshHash in ipairs(redis.call('smembers', KEYS[3])) do
                            if redis.call('exists', 'refresh:' .. refreshHash) == 0 then
                                redis.call('srem', KEYS[3], refreshHash)
                            end
                        end
                    end
                    if accessSetType == 'set' then
                        for _, accessHash in ipairs(redis.call('smembers', KEYS[5])) do
                            local accessKey = 'user:access:' .. ARGV[1] .. ':' .. accessHash
                            if redis.call('exists', accessKey) == 0 then
                                redis.call('srem', KEYS[5], accessHash)
                            end
                        end
                    end
                    if redis.call('scard', KEYS[3]) >= maxActivePairs
                            or redis.call('scard', KEYS[5]) >= maxActivePairs then
                        return -3
                    end

                    redis.call('psetex', KEYS[1], ARGV[4], ARGV[1])
                    redis.call('psetex', KEYS[2], ARGV[4], ARGV[3])
                    redis.call('sadd', KEYS[3], ARGV[2])
                    extendTtl(KEYS[3], refreshTtl)
                    redis.call('psetex', KEYS[4], ARGV[5], 'active')
                    redis.call('sadd', KEYS[5], ARGV[3])
                    extendTtl(KEYS[5], accessTtl)
                    return 1
                    """,
            Long.class
    );
    private static final DefaultRedisScript<Long> REVOKE_REFRESH_TOKEN_SCRIPT = new DefaultRedisScript<>(
            """
                    local fallbackAccessTtl = tonumber(ARGV[3])
                    local providedAccessTtl = tonumber(ARGV[5])
                    if not fallbackAccessTtl or fallbackAccessTtl <= 0
                            or not providedAccessTtl or providedAccessTtl < 0 then
                        return -2
                    end

                    local refreshSetType = redis.call('type', KEYS[3]).ok
                    local retrySetType = redis.call('type', KEYS[5]).ok
                    local retryOwnerType = redis.call('type', KEYS[4]).ok
                    local rotationType = redis.call('type', KEYS[6]).ok
                    local accessSetType = redis.call('type', KEYS[7]).ok
                    local refreshType = redis.call('type', KEYS[1]).ok
                    local mappingType = redis.call('type', KEYS[2]).ok
                    if refreshSetType ~= 'none' and refreshSetType ~= 'set' then
                        return -2
                    end
                    if retrySetType ~= 'none' and retrySetType ~= 'set' then
                        return -2
                    end
                    if retryOwnerType ~= 'none' and retryOwnerType ~= 'string' then
                        return -2
                    end
                    if rotationType ~= 'none' and rotationType ~= 'string' then
                        return -2
                    end
                    if accessSetType ~= 'none' and accessSetType ~= 'set' then
                        return -2
                    end
                    if refreshType ~= 'none' and refreshType ~= 'string' then
                        return -2
                    end
                    if mappingType ~= 'none' and mappingType ~= 'string' then
                        return -2
                    end
                    local function revokeAccess(accessHash, accessKey, requestedTtl)
                        local remainingAccessTtl = redis.call('pttl', accessKey)
                        if requestedTtl > remainingAccessTtl then
                            remainingAccessTtl = requestedTtl
                        end
                        if remainingAccessTtl <= 0 then
                            remainingAccessTtl = fallbackAccessTtl
                        end
                        local blacklistKey = 'blacklist:' .. accessHash
                        local currentBlacklistTtl = redis.call('pttl', blacklistKey)
                        if currentBlacklistTtl > remainingAccessTtl then
                            remainingAccessTtl = currentBlacklistTtl
                        end
                        redis.call('psetex', blacklistKey, remainingAccessTtl, 'blacklisted')
                        redis.call('del', accessKey)
                        if accessSetType == 'set' then
                            redis.call('srem', KEYS[7], accessHash)
                        end
                    end

                    local function blacklistProvidedAccess()
                        if providedAccessTtl > 0 then
                            revokeAccess(ARGV[4], KEYS[9], providedAccessTtl)
                        end
                    end

                    local function parseRotationValue(value)
                        local userId, successorHash, accessHash = string.match(
                                value,
                                '^([^|]+)|([^|]+)|([^|]+)$'
                        )
                        if userId then
                            return userId, successorHash, accessHash
                        end
                        return value, nil, nil
                    end

                    local rotationValue = redis.call('get', KEYS[6])
                    if rotationValue then
                        local rotationUserId, rotationSuccessorHash, rotationAccessHash =
                                parseRotationValue(rotationValue)
                        if rotationUserId ~= ARGV[1] then
                            return -2
                        end
                        if rotationAccessHash and rotationAccessHash ~= 'active'
                                and providedAccessTtl > 0 and rotationAccessHash ~= ARGV[4] then
                            return -4
                        end

                        local successorRefreshHash = nil
                        local successorRefreshKey = nil
                        local successorMappingKey = nil
                        local successorOwnerKey = nil
                        local successorAccessHash = nil
                        local successorAccessKey = nil
                        local successorPredecessorHash = ARGV[2]

                        if rotationSuccessorHash then
                            local currentHash = rotationSuccessorHash
                            local visitedHashes = {}
                            local traversalComplete = false
                            for _ = 1, 1000 do
                                if visitedHashes[currentHash] then
                                    return -2
                                end
                                visitedHashes[currentHash] = true
                                local currentRefreshKey = 'refresh:' .. currentHash
                                local currentRefreshType = redis.call('type', currentRefreshKey).ok
                                if currentRefreshType == 'string' then
                                    successorRefreshHash = currentHash
                                    successorRefreshKey = currentRefreshKey
                                    successorMappingKey = 'user:tokens:' .. ARGV[1]
                                            .. ':' .. currentHash
                                    successorOwnerKey = 'refresh:retry:owner:' .. currentHash
                                    traversalComplete = true
                                    break
                                end
                                if currentRefreshType ~= 'none' then
                                    return -2
                                end

                                local currentRotationKey = 'rotation:' .. currentHash
                                local currentRotationType = redis.call(
                                        'type',
                                        currentRotationKey
                                ).ok
                                if currentRotationType == 'none' then
                                    traversalComplete = true
                                    break
                                end
                                if currentRotationType ~= 'string' then
                                    return -2
                                end
                                local nextUserId, nextHash = parseRotationValue(
                                        redis.call('get', currentRotationKey)
                                )
                                if nextUserId ~= ARGV[1] then
                                    return -2
                                end
                                if not nextHash then
                                    traversalComplete = true
                                    break
                                end
                                successorPredecessorHash = currentHash
                                currentHash = nextHash
                            end
                            if not traversalComplete then
                                return -2
                            end
                        elseif refreshSetType == 'set' then
                            for _, refreshHash in ipairs(redis.call('smembers', KEYS[3])) do
                                local ownerKey = 'refresh:retry:owner:' .. refreshHash
                                local ownerType = redis.call('type', ownerKey).ok
                                if ownerType ~= 'none' and ownerType ~= 'string' then
                                    return -2
                                end
                                if ownerType == 'string'
                                        and redis.call('get', ownerKey) == ARGV[2] then
                                    if successorRefreshHash then
                                        return -2
                                    end
                                    successorRefreshHash = refreshHash
                                    successorRefreshKey = 'refresh:' .. refreshHash
                                    successorMappingKey = 'user:tokens:' .. ARGV[1] .. ':' .. refreshHash
                                    successorOwnerKey = ownerKey
                                end
                            end
                        end

                        if successorRefreshHash then
                            local successorRefreshType = redis.call('type', successorRefreshKey).ok
                            local successorMappingType = redis.call('type', successorMappingKey).ok
                            if successorRefreshType ~= 'string'
                                    or redis.call('get', successorRefreshKey) ~= ARGV[1]
                                    or successorMappingType ~= 'string'
                                    or refreshSetType ~= 'set'
                                    or redis.call(
                                            'sismember',
                                            KEYS[3],
                                            successorRefreshHash
                                    ) ~= 1 then
                                return -2
                            end
                            local successorOwnerType = redis.call('type', successorOwnerKey).ok
                            if successorOwnerType ~= 'none'
                                    and successorOwnerType ~= 'string' then
                                return -2
                            end
                            successorAccessHash = redis.call('get', successorMappingKey)
                            if not successorAccessHash or successorAccessHash == '' then
                                return -2
                            end
                            if successorAccessHash ~= 'active' then
                                successorAccessKey = 'user:access:' .. ARGV[1]
                                        .. ':' .. successorAccessHash
                                local successorAccessType = redis.call('type', successorAccessKey).ok
                                local successorBlacklistType = redis.call(
                                        'type',
                                        'blacklist:' .. successorAccessHash
                                ).ok
                                if successorAccessType ~= 'none'
                                        and successorAccessType ~= 'string' then
                                    return -2
                                end
                                if successorBlacklistType ~= 'none'
                                        and successorBlacklistType ~= 'string' then
                                    return -2
                                end
                            end
                        end

                        if successorRefreshHash then
                            redis.call('del', successorRefreshKey)
                            redis.call('del', successorMappingKey)
                            redis.call('del', successorOwnerKey)
                            redis.call('srem', KEYS[3], successorRefreshHash)
                            if successorAccessKey then
                                revokeAccess(successorAccessHash, successorAccessKey, 0)
                            end
                        end
                        redis.call('del', 'refresh:retry:' .. successorPredecessorHash)
                        redis.call('srem', KEYS[5], successorPredecessorHash)
                        if successorPredecessorHash ~= ARGV[2] then
                            redis.call('del', 'refresh:retry:' .. ARGV[2])
                            redis.call('srem', KEYS[5], ARGV[2])
                        end
                        redis.call('del', KEYS[4])
                        redis.call('del', KEYS[1])
                        redis.call('del', KEYS[2])
                        redis.call('srem', KEYS[3], ARGV[2])
                        return 2
                    end

                    if providedAccessTtl > 0 then
                        local providedBlacklistType = redis.call('type', KEYS[8]).ok
                        local providedAccessType = redis.call('type', KEYS[9]).ok
                        if providedBlacklistType ~= 'none' and providedBlacklistType ~= 'string' then
                            return -2
                        end
                        if providedAccessType ~= 'none' and providedAccessType ~= 'string' then
                            return -2
                        end
                    end

                    local mappedAccessHash = nil
                    local mappedAccessKey = nil
                    local legacyMapping = false
                    if mappingType == 'string' then
                        mappedAccessHash = redis.call('get', KEYS[2])
                        if not mappedAccessHash or mappedAccessHash == '' then
                            return -2
                        end
                        if mappedAccessHash ~= 'active' then
                            if providedAccessTtl > 0 and mappedAccessHash ~= ARGV[4] then
                                return -4
                            end
                            mappedAccessKey = 'user:access:' .. ARGV[1] .. ':' .. mappedAccessHash
                            local mappedAccessType = redis.call('type', mappedAccessKey).ok
                            local mappedBlacklistType = redis.call(
                                    'type',
                                    'blacklist:' .. mappedAccessHash
                            ).ok
                            if mappedAccessType ~= 'none' and mappedAccessType ~= 'string' then
                                return -2
                            end
                            if mappedBlacklistType ~= 'none'
                                    and mappedBlacklistType ~= 'string' then
                                return -2
                            end
                        else
                            legacyMapping = true
                        end
                    elseif refreshType == 'string' then
                        return -2
                    end

                    local oldRefreshHash = redis.call('get', KEYS[4])
                    if oldRefreshHash then
                        redis.call('del', 'refresh:retry:' .. oldRefreshHash)
                        redis.call('srem', KEYS[5], oldRefreshHash)
                    end
                    redis.call('del', KEYS[4])
                    redis.call('del', KEYS[1])
                    redis.call('del', KEYS[2])
                    redis.call('srem', KEYS[3], ARGV[2])
                    if mappedAccessKey then
                        local mappedAccessTtl = 0
                        if providedAccessTtl > 0 then
                            mappedAccessTtl = providedAccessTtl
                        end
                        revokeAccess(mappedAccessHash, mappedAccessKey, mappedAccessTtl)
                    elseif legacyMapping then
                        blacklistProvidedAccess()
                    end
                    return 1
                    """,
            Long.class
    );
    private static final DefaultRedisScript<Long> REVOKE_ALL_USER_TOKENS_SCRIPT = new DefaultRedisScript<>(
            """
                    local fallbackAccessTtl = tonumber(ARGV[2])
                    if not fallbackAccessTtl or fallbackAccessTtl <= 0 then
                        return -2
                    end

                    local refreshSetType = redis.call('type', KEYS[1]).ok
                    local accessSetType = redis.call('type', KEYS[2]).ok
                    local retrySetType = redis.call('type', KEYS[3]).ok
                    if refreshSetType ~= 'none' and refreshSetType ~= 'set' then
                        return -2
                    end
                    if accessSetType ~= 'none' and accessSetType ~= 'set' then
                        return -2
                    end
                    if retrySetType ~= 'none' and retrySetType ~= 'set' then
                        return -2
                    end

                    if refreshSetType == 'set' then
                        for _, refreshHash in ipairs(redis.call('smembers', KEYS[1])) do
                            redis.call('del', 'refresh:' .. refreshHash)
                            redis.call('del', 'user:tokens:' .. ARGV[1] .. ':' .. refreshHash)
                            redis.call('del', 'refresh:retry:owner:' .. refreshHash)
                        end
                    end

                    if accessSetType == 'set' then
                        for _, accessHash in ipairs(redis.call('smembers', KEYS[2])) do
                            local accessKey = 'user:access:' .. ARGV[1] .. ':' .. accessHash
                            local remainingAccessTtl = redis.call('pttl', accessKey)
                            if remainingAccessTtl <= 0 then
                                remainingAccessTtl = fallbackAccessTtl
                            end
                            local blacklistKey = 'blacklist:' .. accessHash
                            local currentBlacklistTtl = redis.call('pttl', blacklistKey)
                            if currentBlacklistTtl > remainingAccessTtl then
                                remainingAccessTtl = currentBlacklistTtl
                            end
                            redis.call('psetex', blacklistKey, remainingAccessTtl, 'blacklisted')
                            redis.call('del', accessKey)
                        end
                    end

                    if retrySetType == 'set' then
                        for _, oldRefreshHash in ipairs(redis.call('smembers', KEYS[3])) do
                            redis.call('del', 'refresh:retry:' .. oldRefreshHash)
                        end
                    end

                    redis.call('del', KEYS[1])
                    redis.call('del', KEYS[2])
                    redis.call('del', KEYS[3])
                    return 1
                    """,
            Long.class
    );
    private static final DefaultRedisScript<Long> BLACKLIST_ACCESS_TOKEN_SCRIPT = new DefaultRedisScript<>(
            """
                    local requestedTtl = tonumber(ARGV[2])
                    if not requestedTtl or requestedTtl <= 0 then
                        return -2
                    end
                    local accessSetType = redis.call('type', KEYS[3]).ok
                    if accessSetType ~= 'none' and accessSetType ~= 'set' then
                        return -2
                    end

                    local currentBlacklistTtl = redis.call('pttl', KEYS[1])
                    if currentBlacklistTtl > requestedTtl then
                        requestedTtl = currentBlacklistTtl
                    end
                    redis.call('psetex', KEYS[1], requestedTtl, 'blacklisted')
                    redis.call('del', KEYS[2])
                    redis.call('srem', KEYS[3], ARGV[1])
                    return 1
                    """,
            Long.class
    );
    private static final DefaultRedisScript<Long> BLACKLIST_ALL_USER_ACCESS_TOKENS_SCRIPT =
            new DefaultRedisScript<>(
                    """
                            local fallbackAccessTtl = tonumber(ARGV[2])
                            if not fallbackAccessTtl or fallbackAccessTtl <= 0 then
                                return -2
                            end
                            local accessSetType = redis.call('type', KEYS[1]).ok
                            if accessSetType ~= 'none' and accessSetType ~= 'set' then
                                return -2
                            end
                            if accessSetType == 'none' then
                                return 0
                            end

                            for _, accessHash in ipairs(redis.call('smembers', KEYS[1])) do
                                local accessKey = 'user:access:' .. ARGV[1] .. ':' .. accessHash
                                local remainingAccessTtl = redis.call('pttl', accessKey)
                                if remainingAccessTtl <= 0 then
                                    remainingAccessTtl = fallbackAccessTtl
                                end
                                local blacklistKey = 'blacklist:' .. accessHash
                                local currentBlacklistTtl = redis.call('pttl', blacklistKey)
                                if currentBlacklistTtl > remainingAccessTtl then
                                    remainingAccessTtl = currentBlacklistTtl
                                end
                                redis.call('psetex', blacklistKey, remainingAccessTtl, 'blacklisted')
                                redis.call('del', accessKey)
                            end
                            redis.call('del', KEYS[1])
                            return 1
                            """,
                    Long.class
            );

    @Value("${app.jwt.refresh-retry-ttl-seconds}")
    private long refreshRetryTtlSeconds;

    @Value("${app.jwt.max-active-token-pairs-per-user:100}")
    private long maxActiveTokenPairsPerUser = DEFAULT_MAX_ACTIVE_TOKEN_PAIRS_PER_USER;

    @Override
    public TokenPair generateTokenPair(String username, Long userId, List<String> roles) {
        log.info("Generating token pair for user: username='{}', userId={}, roles={}",
                username, userId, roles);
        String accessToken = generateAccessToken(username, userId, roles);
        String refreshToken = generateRefreshToken(username, userId, roles);

        TokenPair tokenPair = new TokenPair(accessToken, refreshToken);
        storeTokenPairAtomically(tokenPair, userId);
        log.info("Token pair generated successfully for user: userId={}", userId);
        return tokenPair;
    }

    @Override
    public String generateAccessToken(String username, Long userId, List<String> roles) {
        log.debug("Generating access token for user: username='{}', userId={}", username, userId);
        return jwtUtil.generateToken(username, userId, roles);
    }

    @Override
    public String generateRefreshToken(String username, Long userId, List<String> roles) {
        log.debug("Generating refresh token for user: username='{}', userId={}", username, userId);
        return jwtUtil.generateRefreshToken(username, userId, roles);
    }

    @Override
    public boolean validateRefreshToken(String refreshToken) {
        log.info("Start the validation of the refresh token");
        try {
            var payload = jwtUtil.parseRefreshToken(refreshToken);
            if (payload.isEmpty()) {
                log.warn("Refresh token validation failed: invalid payload");
                return false;
            }
            // Check as per hash + Check if token is revoked
            String tokenHash = hashToken(refreshToken);
            String key = REFRESH_PREFIX + tokenHash;
            boolean exists = Boolean.TRUE.equals(redisTemplate.hasKey(key));
            log.debug("Refresh token exists in Redis: {}", exists);
            return exists;
        } catch (Exception e) {
            log.warn("Refresh token validation failed: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public boolean isRefreshTokenReused(String refreshToken) {
        String tokenHash = hashToken(refreshToken);
        String key = REFRESH_ROTATION_PREFIX + tokenHash;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    @Override
    public void revokeRefreshToken(String refreshToken) {
        revokeTokenPairAtomically(refreshToken, null);
    }

    @Override
    public void revokeTokenPair(String refreshToken, String accessToken) {
        revokeTokenPairAtomically(refreshToken, accessToken);
    }

    private void revokeTokenPairAtomically(String refreshToken, String accessToken) {
        log.info("Start the revoke of the refresh token");
        String tokenHash = hashToken(refreshToken);
        Optional<JwtUtil.JwtPayload> payload = jwtUtil.parseRefreshToken(refreshToken);
        if (payload.isEmpty()) {
            redisTemplate.delete(REFRESH_PREFIX + tokenHash);
            log.info("Invalid refresh token removed without unverified access token revocation");
            return;
        }

        String userId = payload.get().userId().toString();
        String accessHash = tokenHash;
        long accessTtl = 0L;
        if (accessToken != null) {
            Optional<JwtUtil.JwtPayload> accessPayload = jwtUtil.parseAccessToken(accessToken);
            if (accessPayload.isPresent()) {
                if (!payload.get().userId().equals(accessPayload.get().userId())) {
                    throw new InvalidTokenException(
                            "Access and refresh tokens belong to different users"
                    );
                }
                accessHash = hashToken(accessToken);
                accessTtl = jwtUtil.getTokenRemainingTime(accessToken);
            }
        }
        List<String> keys = List.of(
                REFRESH_PREFIX + tokenHash,
                USER_TOKENS_PREFIX + userId + ":" + tokenHash,
                USER_REFRESH_SET_PREFIX + userId,
                REFRESH_RETRY_OWNER_PREFIX + tokenHash,
                USER_REFRESH_RETRY_SET_PREFIX + userId,
                REFRESH_ROTATION_PREFIX + tokenHash,
                USER_ACCESS_SET_PREFIX + userId,
                BLACKLIST_PREFIX + accessHash,
                USER_ACCESS_PREFIX + userId + ":" + accessHash
        );
        Long result = redisTemplate.execute(
                REVOKE_REFRESH_TOKEN_SCRIPT,
                keys,
                userId,
                tokenHash,
                Long.toString(requirePositiveTtl(jwtUtil.getExpirationMs(), "Access token TTL")),
                accessHash,
                Long.toString(accessTtl)
        );
        if (Long.valueOf(TOKEN_PAIR_MISMATCH).equals(result)) {
            throw new InvalidTokenException(
                    "Access and refresh tokens do not form a token pair"
            );
        }
        requireSuccessfulScript(result, "Refresh token revocation");
        log.info("Refresh token revoked");
    }

    @Override
    public void revokeAllUserTokens(Long userId) {
        log.info("Revoking all tokens for user: userId={}", userId);
        List<String> keys = List.of(
                USER_REFRESH_SET_PREFIX + userId,
                USER_ACCESS_SET_PREFIX + userId,
                USER_REFRESH_RETRY_SET_PREFIX + userId
        );
        Long result = redisTemplate.execute(
                REVOKE_ALL_USER_TOKENS_SCRIPT,
                keys,
                userId.toString(),
                Long.toString(requirePositiveTtl(jwtUtil.getExpirationMs(), "Access token TTL"))
        );
        requireSuccessfulScript(result, "User token revocation");
        log.info("All tokens revoked for user: {}", userId);
    }

    @Override
    public TokenPair rotateRefreshToken(String oldRefreshToken) {
        log.info("Rotating refresh token atomically");
        Optional<TokenPair> existingRetry = getRefreshRetryResult(oldRefreshToken);
        if (existingRetry.isPresent()) {
            return existingRetry.get();
        }

        var oldPayload = jwtUtil.parseRefreshToken(oldRefreshToken);
        if (oldPayload.isEmpty()) {
            return resolveRejectedRotation(oldRefreshToken, oldPayload);
        }

        long remainingTtl = jwtUtil.getTokenRemainingTime(oldRefreshToken);
        if (remainingTtl <= 0) {
            return resolveRejectedRotation(oldRefreshToken, oldPayload);
        }

        long retryTtlMillis = Math.min(getRefreshRetryTtlMillis(), remainingTtl);
        JwtUtil.JwtPayload payload = oldPayload.get();
        TokenPair candidate = new TokenPair(
                generateAccessToken(payload.username(), payload.userId(), payload.roles()),
                generateRefreshToken(payload.username(), payload.userId(), payload.roles())
        );
        String oldTokenHash = hashToken(oldRefreshToken);
        String newRefreshHash = hashToken(candidate.refreshToken());
        String newAccessHash = hashToken(candidate.accessToken());
        String userId = payload.userId().toString();
        List<String> keys = List.of(
                REFRESH_PREFIX + oldTokenHash,
                REFRESH_ROTATION_PREFIX + oldTokenHash,
                REFRESH_RETRY_PREFIX + oldTokenHash,
                USER_TOKENS_PREFIX + userId + ":" + oldTokenHash,
                USER_REFRESH_SET_PREFIX + userId,
                REFRESH_PREFIX + newRefreshHash,
                USER_TOKENS_PREFIX + userId + ":" + newRefreshHash,
                USER_ACCESS_PREFIX + userId + ":" + newAccessHash,
                USER_ACCESS_SET_PREFIX + userId,
                USER_REFRESH_RETRY_SET_PREFIX + userId,
                REFRESH_RETRY_OWNER_PREFIX + newRefreshHash,
                REFRESH_RETRY_OWNER_PREFIX + oldTokenHash
        );
        String retryValue = candidate.accessToken() + "|" + candidate.refreshToken();

        Long completionResult = redisTemplate.execute(
                ROTATE_REFRESH_TOKEN_SCRIPT,
                keys,
                userId,
                Long.toString(remainingTtl),
                retryValue,
                Long.toString(retryTtlMillis),
                oldTokenHash,
                newRefreshHash,
                newAccessHash,
                Long.toString(requirePositiveTtl(
                        jwtUtil.getTokenRemainingTime(candidate.refreshToken()),
                        "Refresh token TTL"
                )),
                Long.toString(requirePositiveTtl(
                        jwtUtil.getTokenRemainingTime(candidate.accessToken()),
                        "Access token TTL"
                ))
        );

        if (completionResult == null) {
            throw new IllegalStateException("Refresh token rotation returned no result");
        }
        if (completionResult < 0) {
            throw new IllegalStateException("Refresh token rotation state is invalid");
        }
        if (completionResult != ROTATION_COMPLETED) {
            return resolveRejectedRotation(oldRefreshToken, oldPayload);
        }

        log.info("Refresh token rotation completed for user: {}", userId);
        return candidate;
    }

    private TokenPair resolveRejectedRotation(
            String oldRefreshToken,
            Optional<JwtUtil.JwtPayload> oldPayload
    ) {
        Optional<TokenPair> concurrentResult = getRefreshRetryResult(oldRefreshToken);
        if (concurrentResult.isPresent()) {
            log.info("Concurrent refresh rotation won the atomic transition");
            return concurrentResult.get();
        }

        if (isRefreshTokenReused(oldRefreshToken)) {
            oldPayload.ifPresent(payload -> {
                revokeAllUserTokens(payload.userId());
                log.warn("All tokens revoked for user: {} due to refresh token reuse", payload.userId());
            });
            throw new TokenReuseException("Token reuse detected - all tokens revoked");
        }

        throw new InvalidTokenException("Invalid or expired refresh token");
    }

    @Override
    public boolean isAccessTokenBlacklisted(String accessToken) {
        log.info("Checking if token is blacklisted");
        String tokenHash = hashToken(accessToken);
        String key = BLACKLIST_PREFIX + tokenHash;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    @Override
    public void blacklistAccessToken(String accessToken) {
        log.info("Adding token to blacklist");
        var payload = jwtUtil.parseAccessToken(accessToken);
        if (payload.isEmpty()) {
            log.warn("Access token was not blacklisted because it is invalid or expired");
            return;
        }
        long ttl = jwtUtil.getTokenRemainingTime(accessToken);
        if (ttl <= 0) {
            return;
        }
        String tokenHash = hashToken(accessToken);
        String userId = payload.get().userId().toString();
        List<String> keys = List.of(
                BLACKLIST_PREFIX + tokenHash,
                USER_ACCESS_PREFIX + userId + ":" + tokenHash,
                USER_ACCESS_SET_PREFIX + userId
        );
        Long result = redisTemplate.execute(
                BLACKLIST_ACCESS_TOKEN_SCRIPT,
                keys,
                tokenHash,
                Long.toString(ttl)
        );
        requireSuccessfulScript(result, "Access token blacklist");
        log.debug("Access token blacklisted for user: {}", payload.get().userId());
    }

    @Override
    public void blacklistAllUserAccessTokens(Long userId) {
        log.info("Blacklisting all access tokens for user: userId={}", userId);
        Long result = redisTemplate.execute(
                BLACKLIST_ALL_USER_ACCESS_TOKENS_SCRIPT,
                List.of(USER_ACCESS_SET_PREFIX + userId),
                userId.toString(),
                Long.toString(requirePositiveTtl(jwtUtil.getExpirationMs(), "Access token TTL"))
        );
        requireSuccessfulScript(result, "User access token blacklist");
        log.info("All access tokens blacklisted for user: {}", userId);
    }

    private void storeTokenPairAtomically(TokenPair tokenPair, Long userIdValue) {
        String userId = userIdValue.toString();
        String refreshHash = hashToken(tokenPair.refreshToken());
        String accessHash = hashToken(tokenPair.accessToken());
        long refreshTtl = requirePositiveTtl(
                jwtUtil.getTokenRemainingTime(tokenPair.refreshToken()),
                "Refresh token TTL"
        );
        long accessTtl = requirePositiveTtl(
                jwtUtil.getTokenRemainingTime(tokenPair.accessToken()),
                "Access token TTL"
        );
        List<String> keys = List.of(
                REFRESH_PREFIX + refreshHash,
                USER_TOKENS_PREFIX + userId + ":" + refreshHash,
                USER_REFRESH_SET_PREFIX + userId,
                USER_ACCESS_PREFIX + userId + ":" + accessHash,
                USER_ACCESS_SET_PREFIX + userId
        );

        Long result = redisTemplate.execute(
                STORE_TOKEN_PAIR_SCRIPT,
                keys,
                userId,
                refreshHash,
                accessHash,
                Long.toString(refreshTtl),
                Long.toString(accessTtl),
                Long.toString(requirePositiveTtl(
                        maxActiveTokenPairsPerUser,
                        "Maximum active token pairs per user"
                ))
        );
        if (Long.valueOf(TOKEN_LIMIT_EXCEEDED).equals(result)) {
            throw new ActiveTokenLimitExceededException(
                    "Maximum active token pairs per user has been reached"
            );
        }
        requireSuccessfulScript(result, "Token pair storage");
        if (result != ROTATION_COMPLETED) {
            throw new IllegalStateException("Token pair storage was not completed");
        }
    }

    private String hashToken(String token) {
        return TokenHashUtil.hashToken(token);
    }

    @Override
    public Optional<TokenPair> getRefreshRetryResult(String oldRefreshToken) {
        log.debug("Checking refresh retry cache");
        String tokenHash = hashToken(oldRefreshToken);
        String key = REFRESH_RETRY_PREFIX + tokenHash;
        String value = redisTemplate.opsForValue().get(key);

        if (value == null) {
            return Optional.empty();
        }

        // Разделяем accessToken и refreshToken
        String[] parts = value.split("\\|", 2);
        if (parts.length != 2) {
            log.warn("Invalid retry cache value format");
            return Optional.empty();
        }

        log.debug("Refresh retry cache hit");
        return Optional.of(new TokenPair(parts[0], parts[1]));
    }

    private long getRefreshRetryTtlMillis() {
        if (refreshRetryTtlSeconds <= 0) {
            throw new IllegalStateException("Refresh retry TTL must be positive");
        }
        try {
            return Math.multiplyExact(refreshRetryTtlSeconds, TimeUnit.SECONDS.toMillis(1L));
        } catch (ArithmeticException e) {
            throw new IllegalStateException("Refresh retry TTL is too large", e);
        }
    }

    private long requirePositiveTtl(long ttl, String name) {
        if (ttl <= 0) {
            throw new IllegalStateException(name + " must be positive");
        }
        return ttl;
    }

    private void requireSuccessfulScript(Long result, String operation) {
        if (result == null) {
            throw new IllegalStateException(operation + " returned no result");
        }
        if (result < 0) {
            throw new IllegalStateException(operation + " encountered invalid Redis state");
        }
    }
}
