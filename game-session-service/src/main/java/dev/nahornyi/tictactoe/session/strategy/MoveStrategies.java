package dev.nahornyi.tictactoe.session.strategy;

import dev.nahornyi.tictactoe.session.config.SessionProperties;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Resolves a strategy name to an implementation. Every {@link MoveStrategy} bean registers itself
 * here simply by existing, so adding one is a single new class.
 */
@Component
public class MoveStrategies {

    private final Map<String, MoveStrategy> byName;
    private final MoveStrategy defaultStrategy;

    public MoveStrategies(List<MoveStrategy> strategies, SessionProperties properties) {
        this.byName = strategies.stream().collect(Collectors.toMap(
                strategy -> strategy.name().toLowerCase(Locale.ROOT),
                Function.identity(),
                (first, second) -> first,
                LinkedHashMap::new));

        MoveStrategy configured = byName.get(properties.defaultStrategy().toLowerCase(Locale.ROOT));
        if (configured == null) {
            // Fail at startup rather than on the first request: a typo in configuration should not
            // wait for a user to discover it.
            throw new IllegalStateException("Configured default strategy '%s' is not one of %s"
                    .formatted(properties.defaultStrategy(), byName.keySet()));
        }
        this.defaultStrategy = configured;
    }

    /**
     * @param name a requested strategy, or {@code null}/blank for the configured default
     * @throws UnknownStrategyException if the name is not registered
     */
    public MoveStrategy resolve(String name) {
        if (name == null || name.isBlank()) {
            return defaultStrategy;
        }
        MoveStrategy strategy = byName.get(name.toLowerCase(Locale.ROOT));
        if (strategy == null) {
            throw new UnknownStrategyException(name, byName.keySet());
        }
        return strategy;
    }

    public Set<String> available() {
        return byName.keySet();
    }

    public static class UnknownStrategyException extends RuntimeException {
        public UnknownStrategyException(String requested, Set<String> available) {
            super("Unknown strategy '%s'. Available: %s".formatted(requested, available));
        }
    }
}
