package ai.pipestream.search.index.doc;

import ai.pipestream.search.schema.CompiledField;
import ai.pipestream.search.schema.CompiledSchema;
import ai.pipestream.search.v1alpha1.VectorSimilarity;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.DoubleDocValuesField;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.Field;
import org.apache.lucene.document.KnnFloatVectorField;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedNumericDocValuesField;
import org.apache.lucene.document.SortedSetDocValuesField;
import org.apache.lucene.document.StoredField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.NumericUtils;

import java.util.List;

/**
 * Encodes schema-typed values onto a Lucene document, following the
 * index-encoding contract declared on {@code QueryCompiler}: keyword and
 * boolean ("T"/"F") as unanalyzed StringField terms, int64/date as LongPoint
 * (date = epoch millis UTC), double as DoublePoint, text analyzed, vectors as
 * KnnFloatVectorField.
 */
public final class LuceneFieldEncoder {

    private LuceneFieldEncoder() {
    }

    /** A value that contradicts the declared schema type. */
    public static final class EncodingException extends RuntimeException {
        public EncodingException(String message) {
            super(message);
        }
    }

    public static void addString(Document doc, CompiledField field, String value) {
        String name = field.indexName();
        switch (field.type()) {
            case KEYWORD -> {
                doc.add(new StringField(name, value, field.stored() ? Field.Store.YES : Field.Store.NO));
                if (field.docValues()) {
                    doc.add(new SortedSetDocValuesField(name, new BytesRef(value)));
                }
            }
            case TEXT -> doc.add(new TextField(name, value,
                    field.stored() ? Field.Store.YES : Field.Store.NO));
            case STORED_ONLY -> doc.add(new StoredField(name, value));
            default -> throw new EncodingException(
                    "Field '" + name + "' is " + field.type() + "; got a string value");
        }
    }

    public static void addLong(Document doc, CompiledField field, long value) {
        String name = field.indexName();
        switch (field.type()) {
            case LONG, DATE -> {
                doc.add(new LongPoint(name, value));
                if (field.stored()) {
                    doc.add(new StoredField(name, value));
                }
                if (field.docValues()) {
                    if (field.repeated()) {
                        doc.add(new SortedNumericDocValuesField(name, value));
                    } else {
                        doc.add(new NumericDocValuesField(name, value));
                    }
                }
            }
            case DOUBLE -> addDouble(doc, field, value);
            default -> throw new EncodingException(
                    "Field '" + name + "' is " + field.type() + "; got an int64 value");
        }
    }

    public static void addDouble(Document doc, CompiledField field, double value) {
        String name = field.indexName();
        if (field.type() != CompiledSchema.Kind.DOUBLE) {
            throw new EncodingException(
                    "Field '" + name + "' is " + field.type() + "; got a double value");
        }
        doc.add(new DoublePoint(name, value));
        if (field.stored()) {
            doc.add(new StoredField(name, value));
        }
        if (field.docValues()) {
            if (field.repeated()) {
                doc.add(new SortedNumericDocValuesField(name, NumericUtils.doubleToSortableLong(value)));
            } else {
                doc.add(new DoubleDocValuesField(name, value));
            }
        }
    }

    public static void addBool(Document doc, CompiledField field, boolean value) {
        String name = field.indexName();
        if (field.type() != CompiledSchema.Kind.BOOL) {
            throw new EncodingException(
                    "Field '" + name + "' is " + field.type() + "; got a bool value");
        }
        doc.add(new StringField(name, value ? "T" : "F",
                field.stored() ? Field.Store.YES : Field.Store.NO));
    }

    public static void addVector(Document doc, CompiledField field, float[] values) {
        String name = field.indexName();
        if (field.type() != CompiledSchema.Kind.VECTOR) {
            throw new EncodingException(
                    "Field '" + name + "' is " + field.type() + "; got a vector value");
        }
        validateVector(name, values, field.vectorDims(), toLucene(field.vectorSimilarity()));
        doc.add(new KnnFloatVectorField(name, values, toLucene(field.vectorSimilarity())));
    }

    public static void validateVector(String name, float[] values, int expectedDims,
                                      VectorSimilarityFunction similarity) {
        if (expectedDims > 0 && values.length != expectedDims) {
            throw new EncodingException("Field '" + name + "': expected "
                    + expectedDims + " dims, got " + values.length);
        }
        boolean allZero = true;
        for (float f : values) {
            if (!Float.isFinite(f)) {
                throw new EncodingException("Field '" + name + "': non-finite vector component");
            }
            if (f != 0.0f) {
                allZero = false;
            }
        }
        if (allZero && similarity == VectorSimilarityFunction.COSINE) {
            throw new EncodingException(
                    "Field '" + name + "': all-zero vector is invalid under COSINE similarity");
        }
    }

    public static VectorSimilarityFunction toLucene(VectorSimilarity similarity) {
        return switch (similarity) {
            case VECTOR_SIMILARITY_DOT_PRODUCT -> VectorSimilarityFunction.DOT_PRODUCT;
            case VECTOR_SIMILARITY_EUCLIDEAN -> VectorSimilarityFunction.EUCLIDEAN;
            case VECTOR_SIMILARITY_MAX_INNER_PRODUCT -> VectorSimilarityFunction.MAXIMUM_INNER_PRODUCT;
            default -> VectorSimilarityFunction.COSINE;
        };
    }

    public static float[] toFloatArray(List<Float> values) {
        float[] arr = new float[values.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = values.get(i);
        }
        return arr;
    }
}
