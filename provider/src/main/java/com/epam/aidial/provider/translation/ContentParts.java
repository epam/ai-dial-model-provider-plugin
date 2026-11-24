package com.epam.aidial.provider.translation;

import com.anthropic.models.beta.messages.BetaBase64ImageSource;
import com.anthropic.models.beta.messages.BetaContentBlockParam;
import com.anthropic.models.beta.messages.BetaContentBlockSource;
import com.anthropic.models.beta.messages.BetaContentBlockSourceContent;
import com.anthropic.models.beta.messages.BetaImageBlockParam;
import com.anthropic.models.beta.messages.BetaRequestDocumentBlock;
import com.anthropic.models.beta.messages.BetaSearchResultBlockParam;
import com.anthropic.models.beta.messages.BetaTextBlockParam;
import com.anthropic.models.beta.messages.BetaToolResultBlockParam;
import com.epam.aidial.provider.api.InvalidRequestException;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputFile;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputText;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/** The parts of a Responses API message that the content blocks of a Messages API turn become. */
final class ContentParts {

    private static final String FILES_API_UNSUPPORTED = "Files uploaded via Anthropic Files API aren't supported.";

    private final Dropped dropped;

    ContentParts(Dropped dropped) {
        this.dropped = dropped;
    }

    /** One {@code input_text} part. */
    static ResponseInputContent text(String text) {
        return ResponseInputContent.ofInputText(
                ResponseInputText.builder().text(text).build());
    }

    /** The parts of a block of a user turn (or of a document's content): none, if it has none. */
    List<ResponseInputContent> of(BetaContentBlockParam block) {
        if (block.isText()) {
            return texts(block.asText().text());
        }
        if (block.isImage()) {
            return image(block.asImage()).map(List::of).orElse(List.of());
        }
        if (block.isDocument()) {
            return document(block.asDocument());
        }
        if (block.isSearchResult()) {
            return List.of(text(searchResult(block.asSearchResult())));
        }
        if (block.isCompaction()) {
            return texts(block.asCompaction().content().orElse(""));
        }
        dropped.add("content block " + AnthropicJson.typeName(block.type()._value()));
        return List.of();
    }

    /** The parts of what a tool answered: a string, or blocks, less the references to tools the API loads. */
    List<ResponseInputContent> toolResult(BetaToolResultBlockParam.Content content) {
        List<ResponseInputContent> parts = new ArrayList<>();
        content.string().ifPresent(text -> parts.addAll(texts(text)));
        for (BetaToolResultBlockParam.Content.Block block : content.blocks().orElse(List.of())) {
            if (block.isText()) {
                parts.addAll(texts(block.asText().text()));
            } else if (block.isImage()) {
                image(block.asImage()).ifPresent(parts::add);
            } else if (block.isDocument()) {
                parts.addAll(document(block.asDocument()));
            } else if (block.isSearchResult()) {
                parts.add(text(searchResult(block.asSearchResult())));
            } else if (!block.isToolReference()) {
                // A reference names a tool for the Anthropic tool search to load: nothing to carry over
                dropped.add("tool result content "
                        + AnthropicJson.typeName(block.type()._value()));
            }
        }
        return parts;
    }

    private static List<ResponseInputContent> texts(String text) {
        return text.isEmpty() ? List.of() : List.of(text(text));
    }

    private Optional<ResponseInputContent> image(BetaImageBlockParam block) {
        BetaImageBlockParam.Source source = block.source();
        String url;
        if (source.isBase64()) {
            BetaBase64ImageSource base64 = source.asBase64();
            String mediaType =
                    base64._mediaType().asKnown().map(m -> m.asString()).orElse("image/png");
            url = "data:" + mediaType + ";base64," + base64.data();
        } else if (source.isUrl()) {
            url = source.asUrl().url();
        } else if (source.isFile()) {
            throw new InvalidRequestException(FILES_API_UNSUPPORTED);
        } else {
            dropped.add("image source " + AnthropicJson.typeName(source.type()._value()));
            return Optional.empty();
        }
        return Optional.of(ResponseInputContent.ofInputImage(ResponseInputImage.builder()
                .imageUrl(url)
                .detail(ResponseInputImage.Detail.AUTO)
                .build()));
    }

    private List<ResponseInputContent> document(BetaRequestDocumentBlock block) {
        BetaRequestDocumentBlock.Source source = block.source();
        if (source.isBase64()) {
            String mediaType =
                    AnthropicJson.string(source.asBase64()._mediaType()).orElse("application/pdf");
            return List.of(ResponseInputContent.ofInputFile(ResponseInputFile.builder()
                    .filename(block.title().filter(t -> !t.isEmpty()).orElse("document.pdf"))
                    .fileData(
                            "data:" + mediaType + ";base64," + source.asBase64().data())
                    .build()));
        }
        if (source.isUrl()) {
            return List.of(ResponseInputContent.ofInputFile(
                    ResponseInputFile.builder().fileUrl(source.asUrl().url()).build()));
        }
        if (source.isText()) {
            return texts(source.asText().data());
        }
        if (source.isContent()) {
            return documentContent(source.asContent().content());
        }
        if (source.isFile()) {
            throw new InvalidRequestException(FILES_API_UNSUPPORTED);
        }
        dropped.add("document source " + AnthropicJson.typeName(source.type()._value()));
        return List.of();
    }

    private List<ResponseInputContent> documentContent(BetaContentBlockSource.Content content) {
        List<ResponseInputContent> parts = new ArrayList<>();
        content.string().ifPresent(text -> parts.addAll(texts(text)));
        for (BetaContentBlockSourceContent block :
                content.betaContentBlockSource().orElse(List.of())) {
            if (block.isText()) {
                parts.addAll(texts(block.asText().text()));
            } else if (block.isImage()) {
                image(block.asImage()).ifPresent(parts::add);
            } else {
                dropped.add("document content block "
                        + AnthropicJson.typeName(block.type()._value()));
            }
        }
        return parts;
    }

    private static String searchResult(BetaSearchResultBlockParam block) {
        String text = block.content().stream()
                .map(BetaTextBlockParam::text)
                .filter(t -> !t.isEmpty())
                .collect(Collectors.joining("\n"));
        return block.title() + "\n" + block.source() + "\n\n" + text;
    }
}
