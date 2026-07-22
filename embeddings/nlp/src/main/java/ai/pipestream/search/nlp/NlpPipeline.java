package ai.pipestream.search.nlp;

import opennlp.tools.document.Annotation;
import opennlp.tools.document.Document;
import opennlp.tools.document.DocumentAnalyzer;
import opennlp.tools.document.LayerKey;
import opennlp.tools.document.Layers;
import opennlp.tools.document.SentenceDetectorAnnotator;
import opennlp.tools.document.TokenizerAnnotator;
import opennlp.tools.tokenize.SimpleTokenizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The OpenNLP document graph as a service to the engine: runs the fork's
 * layered {@link DocumentAnalyzer} over a text and flattens the requested
 * layers into offset-anchored {@link NlpSpan}s the engine can persist per
 * chunk and return on hits.
 *
 * <p>Supported layers grow with the fork. Today: {@code tokens}
 * (SimpleTokenizer, model-free and deterministic) and {@code sentences}
 * (the digest-pinned sentence model shared with the {@code opennlp-sentence}
 * chunker). Every span refers to the ORIGINAL text the pipeline was given,
 * never a derived form, which is what lets a search hit's annotations map
 * back to page/bbox provenance upstream.
 *
 * <p>Determinism contract: annotations for a layer are a pure function of
 * {@code (text, layer, pinned models)}. Model-backed layers therefore only
 * run under a digest pin.
 */
public final class NlpPipeline {

    /** One annotation: a layer name, a span of the original text, and a value. */
    public record NlpSpan(String layer, int start, int end, String value) {
    }

    public static final String LAYER_TOKENS = "tokens";
    public static final String LAYER_SENTENCES = "sentences";
    public static final Set<String> SUPPORTED_LAYERS = Set.of(LAYER_TOKENS, LAYER_SENTENCES);

    private final DocumentAnalyzer analyzer;
    private final Map<String, LayerKey<String>> requested;

    private NlpPipeline(DocumentAnalyzer analyzer, Map<String, LayerKey<String>> requested) {
        this.analyzer = analyzer;
        this.requested = requested;
    }

    /**
     * Builds a pipeline for the requested layers.
     *
     * @param layers layer names from {@link #SUPPORTED_LAYERS}
     * @param boundary the collection's boundary pin; required (as
     *        {@code opennlp:<sha256>}) when {@code sentences} is requested
     * @throws IllegalArgumentException on an unsupported layer or a missing pin
     */
    public static NlpPipeline forLayers(List<String> layers, String boundary) {
        DocumentAnalyzer.Builder builder = DocumentAnalyzer.builder();
        Map<String, LayerKey<String>> requested = new LinkedHashMap<>();
        for (String layer : layers) {
            switch (layer) {
                case LAYER_TOKENS -> {
                    builder.add(new TokenizerAnnotator(SimpleTokenizer.INSTANCE));
                    requested.put(LAYER_TOKENS, Layers.TOKENS);
                }
                case LAYER_SENTENCES -> {
                    builder.add(new SentenceDetectorAnnotator(
                            OpenNlpModels.sentenceDetector(OpenNlpModels.requirePin(boundary))));
                    requested.put(LAYER_SENTENCES, Layers.SENTENCES);
                }
                default -> throw new IllegalArgumentException(
                        "Unsupported NLP layer '" + layer + "'; supported: " + SUPPORTED_LAYERS);
            }
        }
        if (requested.isEmpty()) {
            throw new IllegalArgumentException("At least one NLP layer is required");
        }
        return new NlpPipeline(builder.build(), requested);
    }

    /** Runs the analyzer and flattens the requested layers, document order per layer. */
    public List<NlpSpan> annotate(String text) {
        Document document = analyzer.analyze(text);
        List<NlpSpan> spans = new ArrayList<>();
        for (Map.Entry<String, LayerKey<String>> entry : requested.entrySet()) {
            for (Annotation<String> annotation : document.get(entry.getValue())) {
                spans.add(new NlpSpan(entry.getKey(),
                        annotation.span().getStart(), annotation.span().getEnd(),
                        annotation.value()));
            }
        }
        return spans;
    }

    /** The spans overlapping {@code [start, end)}, offsets unchanged (original text). */
    public static List<NlpSpan> overlapping(List<NlpSpan> spans, int start, int end) {
        List<NlpSpan> out = new ArrayList<>();
        for (NlpSpan span : spans) {
            if (span.start() < end && span.end() > start) {
                out.add(span);
            }
        }
        return out;
    }
}
