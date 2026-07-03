package ai.pipestream.search.query;

import ai.pipestream.search.v1alpha1.AnalyzerRef;
import ai.pipestream.search.v1alpha1.BuiltinAnalyzer;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.analysis.core.KeywordAnalyzer;
import org.apache.lucene.analysis.en.EnglishAnalyzer;
import org.apache.lucene.analysis.standard.StandardAnalyzer;

import java.util.Map;

/**
 * Resolves analyzer names to Lucene analyzers. Minimal built-in set for now:
 * "standard", "english", "keyword". Lucene analyzers are thread-safe, so one
 * instance of each is shared.
 */
@ApplicationScoped
public class AnalyzerRegistry {

    /** Name used when a text field declares no analyzer (proto default). */
    public static final String DEFAULT_ANALYZER = "standard";

    private final Map<String, Analyzer> analyzers = Map.of(
            "standard", new StandardAnalyzer(),
            "english", new EnglishAnalyzer(),
            "keyword", new KeywordAnalyzer());

    /**
     * Resolve an analyzer by registered name.
     *
     * @throws InvalidQueryException if no analyzer is registered under {@code name}
     */
    public Analyzer resolve(String name) {
        Analyzer analyzer = analyzers.get(name);
        if (analyzer == null) {
            throw new InvalidQueryException(
                    "Unknown analyzer '" + name + "'; registered analyzers: "
                            + String.join(", ", analyzers.keySet().stream().sorted().toList()));
        }
        return analyzer;
    }

    /**
     * Resolve a schema AnalyzerRef. Unset or UNSPECIFIED builtins default to
     * "standard"; pluggable analyzers are not supported yet.
     */
    public Analyzer resolve(AnalyzerRef ref) {
        return switch (ref.getAnalyzerCase()) {
            case BUILTIN -> resolve(builtinName(ref.getBuiltin()));
            case PLUGIN -> throw new InvalidQueryException(
                    "Pluggable analyzer '" + ref.getPlugin().getName() + "' is not supported yet");
            case ANALYZER_NOT_SET -> resolve(DEFAULT_ANALYZER);
        };
    }

    private static String builtinName(BuiltinAnalyzer builtin) {
        return switch (builtin) {
            case BUILTIN_ANALYZER_UNSPECIFIED, BUILTIN_ANALYZER_STANDARD -> "standard";
            case BUILTIN_ANALYZER_ENGLISH -> "english";
            case BUILTIN_ANALYZER_KEYWORD -> "keyword";
            // Declared in the proto but not registered yet; resolve() reports them clearly.
            case BUILTIN_ANALYZER_WHITESPACE -> "whitespace";
            case BUILTIN_ANALYZER_SIMPLE -> "simple";
            case UNRECOGNIZED -> throw new InvalidQueryException("Unrecognized builtin analyzer");
        };
    }
}
