/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.annotation.processing;

import io.micronaut.annotation.processing.visitor.JavaVisitorContext;
import io.micronaut.core.convert.value.MutableConvertibleValues;
import io.micronaut.core.convert.value.MutableConvertibleValuesMap;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.inject.annotation.AbstractAnnotationMetadataBuilder;
import io.micronaut.core.annotation.NonNull;
import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Filer;
import javax.annotation.processing.Messager;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;
import javax.tools.Diagnostic;
import java.util.*;

/**
 * Abstract annotation processor base class.
 *
 * @author Graeme Rocher
 * @since 1.0
 */
abstract class AbstractInjectAnnotationProcessor extends AbstractProcessor {

    /**
     * Annotation processor option used to activate incremental processing.
     */
    protected static final String MICRONAUT_PROCESSING_INCREMENTAL = "micronaut.processing.incremental";

    /**
     * Annotation processor option used to add additional annotation patterns to process.
     */
    protected static final String MICRONAUT_PROCESSING_ANNOTATIONS = "micronaut.processing.annotations";
    /**
     * Constant for aggregating processor.
     */
    protected static final String GRADLE_PROCESSING_AGGREGATING = "org.gradle.annotation.processing.aggregating";
    /**
     * Constant for isolating processor.
     */
    protected static final String GRADLE_PROCESSING_ISOLATING = "org.gradle.annotation.processing.isolating";

    protected Messager messager;
    protected Filer filer;
    protected Elements elementUtils;
    protected Types typeUtils;
    protected AnnotationUtils annotationUtils;
    protected GenericUtils genericUtils;
    protected ModelUtils modelUtils;
    protected MutableConvertibleValues<Object> visitorAttributes = new MutableConvertibleValuesMap<>();
    protected AnnotationProcessingOutputVisitor classWriterOutputVisitor;
    protected JavaVisitorContext javaVisitorContext;
    private boolean incremental = false;
    private final Set<String> supportedAnnotationTypes = new HashSet<>(5);

    @Override
    public SourceVersion getSupportedSourceVersion() {
        SourceVersion sourceVersion = SourceVersion.latest();
        if (sourceVersion.ordinal() <= 15) {
            if (sourceVersion.ordinal() >= 8) {
                return sourceVersion;
            } else {
                return SourceVersion.RELEASE_8;
            }
        } else {
            return (SourceVersion.values())[15];
        }
    }

    @Override
    public Set<String> getSupportedOptions() {
        final Set<String> options;
        if (incremental) {
            options = CollectionUtils.setOf(getIncrementalProcessorType());
        } else {
            options = new HashSet<>(5);
        }
        options.addAll(super.getSupportedOptions());
        return options;
    }

    /**
     *
     * @return The incremental processor type.
     * @see #GRADLE_PROCESSING_AGGREGATING
     * @see #GRADLE_PROCESSING_ISOLATING
     */
    protected String getIncrementalProcessorType() {
        return GRADLE_PROCESSING_ISOLATING;
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        if (incremental) {
            final Set<String> types = CollectionUtils.setOf(
                    "javax.inject.*",
                    "io.micronaut.*"
            );
            types.addAll(supportedAnnotationTypes);
            Set<String> mappedAnnotationNames = AbstractAnnotationMetadataBuilder.getMappedAnnotationNames();
            for (String mappedAnnotationName : mappedAnnotationNames) {
                if (!mappedAnnotationName.contains("Nullable") && !mappedAnnotationName.contains("NotNull")) {
                    types.add(mappedAnnotationName);
                }
            }
            return types;
        } else {
            return Collections.singleton("*");
        }
    }

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
        this.filer = processingEnv.getFiler();
        this.classWriterOutputVisitor = new AnnotationProcessingOutputVisitor(filer);
        this.elementUtils = processingEnv.getElementUtils();
        this.typeUtils = processingEnv.getTypeUtils();
        this.modelUtils = new ModelUtils(elementUtils, typeUtils);
        this.genericUtils = new GenericUtils(
                elementUtils,
                typeUtils,
                modelUtils
        );

        this.annotationUtils = new AnnotationUtils(
                processingEnv,
                elementUtils,
                messager,
                typeUtils,
                modelUtils,
                genericUtils,
                filer,
                visitorAttributes
        );

        this.javaVisitorContext = newVisitorContext(processingEnv);

        this.incremental = isIncremental(processingEnv);
        if (incremental) {
            final String annotations = processingEnv.getOptions().get(MICRONAUT_PROCESSING_ANNOTATIONS);
            if (annotations != null) {
                final String[] tokens = annotations.split(",");
                supportedAnnotationTypes.addAll(Arrays.asList(tokens));
            }
        }
    }

    /**
     * Creates the visitor context.
     *
     * @param processingEnv The processing env
     * @return The context
     */
    @NonNull
    protected JavaVisitorContext newVisitorContext(@NonNull ProcessingEnvironment processingEnv) {
        return new JavaVisitorContext(
                processingEnv,
                messager,
                elementUtils,
                annotationUtils,
                typeUtils,
                modelUtils,
                genericUtils,
                filer,
                visitorAttributes
        );
    }

    /**
     * Produce a compile error for the given element and message.
     *
     * @param e    The element
     * @param msg  The message
     * @param args The string format args
     */
    protected final void error(Element e, String msg, Object... args) {
        if (messager == null) {
            illegalState();
            return;
        }
        messager.printMessage(Diagnostic.Kind.ERROR, String.format(msg, args), e);
    }

    /**
     * Produce a compile error for the given message.
     *
     * @param msg  The message
     * @param args The string format args
     */
    protected final void error(String msg, Object... args) {
        if (messager == null) {
            illegalState();
        }
        messager.printMessage(Diagnostic.Kind.ERROR, String.format(msg, args));
    }

    /**
     * Produce a compile warning for the given element and message.
     *
     * @param e    The element
     * @param msg  The message
     * @param args The string format args
     */
    protected final void warning(Element e, String msg, Object... args) {
        if (messager == null) {
            illegalState();
        }
        messager.printMessage(Diagnostic.Kind.WARNING, String.format(msg, args), e);
    }

    /**
     * Produce a compile warning for the given message.
     *
     * @param msg  The message
     * @param args The string format args
     */
    @SuppressWarnings("WeakerAccess")
    protected final void warning(String msg, Object... args) {
        if (messager == null) {
            illegalState();
        }
        messager.printMessage(Diagnostic.Kind.WARNING, String.format(msg, args));
    }

    /**
     * Produce a compile note for the given element and message.
     *
     * @param e    The element
     * @param msg  The message
     * @param args The string format args
     */
    protected final void note(Element e, String msg, Object... args) {
        if (messager == null) {
            illegalState();
        }
        messager.printMessage(Diagnostic.Kind.NOTE, String.format(msg, args), e);
    }

    /**
     * Produce a compile note for the given element and message.
     *
     * @param msg  The message
     * @param args The string format args
     */
    protected final void note(String msg, Object... args) {
        if (messager == null) {
            illegalState();
        }
        messager.printMessage(Diagnostic.Kind.NOTE, String.format(msg, args));
    }

    private void illegalState() {
        throw new IllegalStateException("No messager set. Ensure processing environment is initialized");
    }

    /**
     * Whether incremental compilation is enabled.
     * @param processingEnv The processing environment.
     * @return True if it is
     */
    protected boolean isIncremental(@NonNull ProcessingEnvironment processingEnv) {
        final Map<String, String> options = processingEnv.getOptions();
        final String v = options.get(MICRONAUT_PROCESSING_INCREMENTAL);
        if (v != null) {
            return Boolean.parseBoolean(v);
        }
        return false;
    }
}
