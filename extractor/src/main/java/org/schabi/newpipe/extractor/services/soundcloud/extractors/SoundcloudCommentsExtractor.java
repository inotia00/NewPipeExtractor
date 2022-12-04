package org.schabi.newpipe.extractor.services.soundcloud.extractors;

import static org.schabi.newpipe.extractor.utils.Utils.isNullOrEmpty;

import com.grack.nanojson.JsonArray;
import com.grack.nanojson.JsonObject;
import com.grack.nanojson.JsonParser;
import com.grack.nanojson.JsonParserException;

import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.comments.CommentsExtractor;
import org.schabi.newpipe.extractor.comments.CommentsInfoItem;
import org.schabi.newpipe.extractor.comments.CommentsInfoItemsCollector;
import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.exceptions.ParsingException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.services.soundcloud.SoundcloudParsingHelper;

import java.io.IOException;

import javax.annotation.Nonnull;

public class SoundcloudCommentsExtractor extends CommentsExtractor {
    public static final String COLLECTION = "collection";
    public static final String NEXT_HREF = "next_href";

    public SoundcloudCommentsExtractor(final StreamingService service,
                                       final ListLinkHandler uiHandler) {
        super(service, uiHandler);
    }

    @Nonnull
    @Override
    public InfoItemsPage<CommentsInfoItem> getInitialPage() throws ExtractionException,
            IOException {
        final Downloader downloader = NewPipe.getDownloader();
        final Response response = downloader.get(getUrl());

        final JsonObject json;
        try {
            json = JsonParser.object().from(response.responseBody());
        } catch (final JsonParserException e) {
            throw new ParsingException("Could not parse json", e);
        }

        final CommentsInfoItemsCollector collector = new CommentsInfoItemsCollector(
                getServiceId());

        collectCommentsFrom(collector, json);

        return new InfoItemsPage<>(collector, new Page(json.getString(NEXT_HREF)));
    }

    @Override
    public InfoItemsPage<CommentsInfoItem> getPage(final Page page) throws ExtractionException,
            IOException {
        if (page == null || isNullOrEmpty(page.getUrl())) {
            throw new IllegalArgumentException("Page doesn't contain an URL");
        }
        final JsonObject json;
        final CommentsInfoItemsCollector collector = new CommentsInfoItemsCollector(
                getServiceId());

        // Replies typically do not have a next page, but that's not always the case.
        final boolean hasNextPage;
        if (page.hasContent()) {
            // This page contains the whole previously fetched comments.
            // We need to get the comments which are replies to the comment with the page's id.
            json = (JsonObject) page.getContent();
            try {
                final int commentId = Integer.parseInt(page.getId());
                hasNextPage = collectRepliesFrom(collector, json, commentId, page.getUrl());
            } catch (final NumberFormatException e) {
                throw new ParsingException("Got invalid comment id", e);
            }
        } else {

            final Downloader downloader = NewPipe.getDownloader();
            final Response response = downloader.get(page.getUrl());

            try {
                json = JsonParser.object().from(response.responseBody());
                hasNextPage = json.has(NEXT_HREF);
            } catch (final JsonParserException e) {
                throw new ParsingException("Could not parse json", e);
            }
            collectCommentsFrom(collector, json);
        }

        if (hasNextPage) {
            return new InfoItemsPage<>(collector, new Page(json.getString(NEXT_HREF)));
        } else {
            return new InfoItemsPage<>(collector, null);
        }
    }

    @Override
    public void onFetchPage(@Nonnull final Downloader downloader) { }

    private void collectCommentsFrom(final CommentsInfoItemsCollector collector,
                                     final JsonObject json) throws ParsingException {
        final String url = getUrl();
        final JsonArray entries = json.getArray(COLLECTION);
        JsonObject lastTopComment = null;
        for (int i = 0; i < entries.size(); i++) {
            final JsonObject entry = entries.getObject(i);
            if (i == 0
                    || (!SoundcloudParsingHelper.isReplyTo(entries.getObject(i - 1), entry)
                    && !SoundcloudParsingHelper.isReplyTo(lastTopComment, entry))) {
                lastTopComment = entry;
                collector.commit(new SoundcloudCommentsInfoItemExtractor(
                        json, i, entry, url));
            }
        }
    }

    private boolean collectRepliesFrom(final CommentsInfoItemsCollector collector,
                                    final JsonObject json,
                                    final int id,
                                    final String url) {
        JsonObject originalComment = null;
        final JsonArray entries = json.getArray(COLLECTION);
        boolean moreReplies = false;
        for (int i = 0; i < entries.size(); i++) {
            final JsonObject comment = entries.getObject(i);
            if (comment.getInt("id") == id) {
                originalComment = comment;
                continue;
            }
            if (originalComment != null
                    && SoundcloudParsingHelper.isReplyTo(originalComment, comment)) {
                collector.commit(new SoundcloudCommentsInfoItemExtractor(
                        json, i, entries.getObject(i), url, originalComment));
                // There might be more replies to the originalComment,
                // especially if the original comment is at the end of the list.
                if (i == entries.size() - 1 && json.has(NEXT_HREF)) {
                    moreReplies = true;
                }
            }
        }
        return moreReplies;
    }

}
