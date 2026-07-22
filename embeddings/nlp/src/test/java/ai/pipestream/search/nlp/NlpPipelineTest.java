package ai.pipestream.search.nlp;

import ai.pipestream.search.nlp.NlpPipeline.NlpSpan;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;

/**
 * Pipeline proofs that need no model: the tokens layer (SimpleTokenizer,
 * model-free), offset fidelity against the original text, chunk-overlap
 * slicing, and the layer/pin validation. Model-backed layers are covered by
 * the chunker test, which trains and pins a real sentence model.
 */
class NlpPipelineTest {

    @Test
    void tokensLayerCarriesOriginalTextOffsets() {
        String text = "Alpha bravo, charlie.";
        List<NlpSpan> spans = NlpPipeline.forLayers(List.of("tokens"), "").annotate(text);

        Assertions.assertFalse(spans.isEmpty());
        for (NlpSpan span : spans) {
            Assertions.assertEquals("tokens", span.layer());
            Assertions.assertEquals(text.substring(span.start(), span.end()), span.value(),
                    "every token value must reconstruct from its offsets");
        }
        Assertions.assertEquals("Alpha", spans.get(0).value());
    }

    @Test
    void overlappingSelectsChunkSpansWithoutRebasing() {
        List<NlpSpan> spans = List.of(
                new NlpSpan("tokens", 0, 5, "Alpha"),
                new NlpSpan("tokens", 6, 11, "bravo"),
                new NlpSpan("tokens", 12, 19, "charlie"));

        List<NlpSpan> middle = NlpPipeline.overlapping(spans, 6, 11);
        Assertions.assertEquals(List.of(spans.get(1)), middle);
        Assertions.assertEquals(6, middle.get(0).start(),
                "offsets stay in the ORIGINAL text, never chunk-local");

        Assertions.assertEquals(List.of(spans.get(0), spans.get(1)),
                NlpPipeline.overlapping(spans, 3, 8),
                "spans straddling the boundary belong to both chunks");
    }

    @Test
    void unsupportedLayersAndMissingPinsFailLoud() {
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> NlpPipeline.forLayers(List.of("coref"), ""),
                "a layer the build cannot produce must be rejected at build time");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> NlpPipeline.forLayers(List.of(), ""),
                "an empty layer list is a configuration error");
        Assertions.assertThrows(IllegalArgumentException.class,
                () -> NlpPipeline.forLayers(List.of("sentences"), "rules-v1"),
                "the sentences layer requires an opennlp model pin");
    }
}
