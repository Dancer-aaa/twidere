/*
 *				Twidere - Twitter client for Android
 * 
 * Copyright (C) 2012 Mariotaku Lee <mariotaku.lee@gmail.com>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.mariotaku.twidere.adapter;

import static org.mariotaku.twidere.util.UserColorNicknameUtils.getUserColor;
import static org.mariotaku.twidere.util.UserColorNicknameUtils.getUserNickname;
import static org.mariotaku.twidere.util.Utils.configBaseCardAdapter;
import static org.mariotaku.twidere.util.Utils.findStatusInDatabases;
import static org.mariotaku.twidere.util.Utils.getAccountColor;
import static org.mariotaku.twidere.util.Utils.getStatusBackground;
import static org.mariotaku.twidere.util.Utils.isFiltered;
import static org.mariotaku.twidere.util.Utils.openImage;
import static org.mariotaku.twidere.util.Utils.openUserProfile;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.Html;
import android.text.TextUtils;
import android.view.View;
import android.view.View.OnClickListener;
import android.view.ViewGroup;

import org.mariotaku.twidere.R;
import org.mariotaku.twidere.adapter.iface.IStatusesAdapter;
import org.mariotaku.twidere.app.TwidereApplication;
import org.mariotaku.twidere.model.CursorStatusIndices;
import org.mariotaku.twidere.model.ParcelableStatus;
import org.mariotaku.twidere.model.ParcelableUserMention;
import org.mariotaku.twidere.provider.TweetStore.Statuses;
import org.mariotaku.twidere.util.ImageLoaderWrapper;
import org.mariotaku.twidere.util.ImageLoadingHandler;
import org.mariotaku.twidere.util.MultiSelectManager;
import org.mariotaku.twidere.util.TwidereLinkify;
import org.mariotaku.twidere.util.Utils;
import org.mariotaku.twidere.view.holder.StatusViewHolder;
import org.mariotaku.twidere.view.iface.ICardItemView.OnOverflowIconClickListener;

public class CursorStatusesAdapter extends BaseCursorAdapter implements IStatusesAdapter<Cursor>, OnClickListener,
		OnOverflowIconClickListener {

	public static final String[] CURSOR_COLS = Statuses.COLUMNS;

	private final Context mContext;
	private final ImageLoaderWrapper mImageLoader;
	private final MultiSelectManager mMultiSelectManager;
	private final SQLiteDatabase mDatabase;
	private final ImageLoadingHandler mImageLoadingHandler;

	private MenuButtonClickListener mListener;

	private boolean mDisplayImagePreview, mGapDisallowed, mMentionsHighlightDisabled, mFavoritesHighlightDisabled,
			mDisplaySensitiveContents, mIndicateMyStatusDisabled, mIsLastItemFiltered, mFiltersEnabled,
			mAnimationEnabled;
	private boolean mFilterIgnoreUser, mFilterIgnoreSource, mFilterIgnoreTextHtml, mFilterIgnoreTextPlain,
			mFilterRetweetedById;
	private int mMaxAnimationPosition;

	private CursorStatusIndices mIndices;

	public CursorStatusesAdapter(final Context context) {
		this(context, Utils.isCompactCards(context));
	}

	public CursorStatusesAdapter(final Context context, final boolean compactCards) {
		super(context, getItemResource(compactCards), null, new String[0], new int[0], 0);
		mContext = context;
		final TwidereApplication application = TwidereApplication.getInstance(context);
		mMultiSelectManager = application.getMultiSelectManager();
		mImageLoader = application.getImageLoaderWrapper();
		mDatabase = application.getSQLiteDatabase();
		mImageLoadingHandler = new ImageLoadingHandler();
		configBaseCardAdapter(context, this);
		setMaxAnimationPosition(-1);
	}

	@Override
	public void bindView(final View view, final Context context, final Cursor cursor) {
		final int position = cursor.getPosition();
		final StatusViewHolder holder = (StatusViewHolder) view.getTag();

		final boolean isGap = cursor.getShort(mIndices.is_gap) == 1;
		final boolean showGap = isGap && !mGapDisallowed && position != getCount() - 1;

		holder.setShowAsGap(showGap);
		holder.position = position;
		holder.setDisplayProfileImage(isDisplayProfileImage());

		if (!showGap) {

			// Clear images in prder to prevent images in recycled view shown.
			holder.profile_image.setImageDrawable(null);
			holder.my_profile_image.setImageDrawable(null);
			holder.image_preview.setImageDrawable(null);

			final TwidereLinkify linkify = getLinkify();
			final boolean showAccountColor = isShowAccountColor();

			final long accountId = cursor.getLong(mIndices.account_id);
			final long userId = cursor.getLong(mIndices.user_id);
			final long timestamp = cursor.getLong(mIndices.status_timestamp);
			final long retweetCount = cursor.getLong(mIndices.retweet_count);
			final long retweetedByUserId = cursor.getLong(mIndices.retweeted_by_user_id);
			final long inReplyToUserId = cursor.getLong(mIndices.in_reply_to_user_id);

			final String retweetedByName = cursor.getString(mIndices.retweeted_by_user_name);
			final String retweetedByScreenName = cursor.getString(mIndices.retweeted_by_user_screen_name);
			final String text = getLinkHighlightOption() != LINK_HIGHLIGHT_OPTION_CODE_NONE ? cursor
					.getString(mIndices.text_html) : cursor.getString(mIndices.text_unescaped);
			final String screen_name = cursor.getString(mIndices.user_screen_name);
			final String name = cursor.getString(mIndices.user_name);
			final String inReplyToName = cursor.getString(mIndices.in_reply_to_user_name);
			final String inReplyToScreenName = cursor.getString(mIndices.in_reply_to_user_screen_name);
			final String mediaLink = cursor.getString(mIndices.media_link);

			// Tweet type (favorite/location/media)
			final boolean isFavorite = cursor.getShort(mIndices.is_favorite) == 1;
			final boolean hasLocation = !TextUtils.isEmpty(cursor.getString(mIndices.location));
			final boolean possiblySensitive = cursor.getInt(mIndices.is_possibly_sensitive) == 1;
			final boolean hasMedia = mediaLink != null;

			// User type (protected/verified)
			final boolean isVerified = cursor.getShort(mIndices.is_verified) == 1;
			final boolean isProtected = cursor.getShort(mIndices.is_protected) == 1;

			final boolean isRetweet = cursor.getShort(mIndices.is_retweet) == 1;
			final boolean isReply = cursor.getLong(mIndices.in_reply_to_status_id) > 0;
			final boolean isMention = ParcelableUserMention.hasMention(cursor.getString(mIndices.mentions), accountId);
			final boolean isMyStatus = accountId == userId;

			holder.setUserColor(getUserColor(mContext, userId));
			holder.setHighlightColor(getStatusBackground(!mMentionsHighlightDisabled && isMention,
					!mFavoritesHighlightDisabled && isFavorite, isRetweet));

			holder.setAccountColorEnabled(showAccountColor);

			if (showAccountColor) {
				holder.setAccountColor(getAccountColor(mContext, accountId));
			}

			holder.setTextSize(getTextSize());

			holder.setIsMyStatus(isMyStatus && !mIndicateMyStatusDisabled);
			if (getLinkHighlightOption() != LINK_HIGHLIGHT_OPTION_CODE_NONE) {
				holder.text.setText(Html.fromHtml(text));
				linkify.applyAllLinks(holder.text, accountId, possiblySensitive);
				holder.text.setMovementMethod(null);
			} else {
				holder.text.setText(text);
			}
			holder.setUserType(isVerified, isProtected);
			holder.setDisplayNameFirst(isDisplayNameFirst());
			holder.setNicknameOnly(isNicknameOnly());
			final String nick = getUserNickname(context, userId);
			holder.name.setText(TextUtils.isEmpty(nick) ? name : isNicknameOnly() ? nick : context.getString(
					R.string.name_with_nickname, name, nick));
			holder.screen_name.setText("@" + screen_name);
			if (getLinkHighlightOption() != LINK_HIGHLIGHT_OPTION_CODE_NONE) {
				linkify.applyUserProfileLinkNoHighlight(holder.name, accountId, userId, screen_name);
				linkify.applyUserProfileLinkNoHighlight(holder.screen_name, accountId, userId, screen_name);
				holder.name.setMovementMethod(null);
				holder.screen_name.setMovementMethod(null);
			}
			holder.time.setTime(timestamp);
			holder.setStatusType(!mFavoritesHighlightDisabled && isFavorite, hasLocation, hasMedia, possiblySensitive);

			holder.setIsReplyRetweet(isReply, isRetweet);
			if (isRetweet) {
				holder.setRetweetedBy(retweetCount, retweetedByUserId, retweetedByName, retweetedByScreenName);
			} else if (isReply) {
				holder.setReplyTo(inReplyToUserId, inReplyToName, inReplyToScreenName);
			}

			if (isDisplayProfileImage()) {
				final String profile_image_url = cursor.getString(mIndices.user_profile_image_url);
				mImageLoader.displayProfileImage(holder.my_profile_image, profile_image_url);
				mImageLoader.displayProfileImage(holder.profile_image, profile_image_url);
				holder.profile_image.setTag(position);
				holder.my_profile_image.setTag(position);
			} else {
				holder.profile_image.setVisibility(View.GONE);
				holder.my_profile_image.setVisibility(View.GONE);
			}
			final boolean hasPreview = mDisplayImagePreview && hasMedia;
			holder.image_preview_container.setVisibility(hasPreview ? View.VISIBLE : View.GONE);
			if (hasPreview && mediaLink != null) {
				if (possiblySensitive && !mDisplaySensitiveContents) {
					holder.image_preview.setImageDrawable(null);
					holder.image_preview.setBackgroundResource(R.drawable.image_preview_nsfw);
					holder.image_preview_progress.setVisibility(View.GONE);
				} else if (!mediaLink.equals(mImageLoadingHandler.getLoadingUri(holder.image_preview))) {
					holder.image_preview.setBackgroundResource(0);
					mImageLoader.displayPreviewImage(holder.image_preview, mediaLink, mImageLoadingHandler);
				}
				holder.image_preview.setTag(position);
			}
		}
	}

	@Override
	public int findPositionByStatusId(final long status_id) {
		final Cursor c = getCursor();
		if (c == null || c.isClosed()) return -1;
		for (int i = 0, count = c.getCount(); i < count; i++) {
			if (c.moveToPosition(i) && c.getLong(mIndices.status_id) == status_id) return i;
		}
		return -1;
	}

	@Override
	public long getAccountId(final int position) {
		final Cursor c = getCursor();
		if (c == null || c.isClosed() || !c.moveToPosition(position)) return -1;
		return c.getLong(mIndices.account_id);
	}

	@Override
	public int getActualCount() {
		return super.getCount();
	}

	@Override
	public int getCount() {
		final int count = super.getCount();
		return mFiltersEnabled && mIsLastItemFiltered && count > 0 ? count - 1 : count;
	}

	@Override
	public ParcelableStatus getLastStatus() {
		final Cursor c = getCursor();
		if (c == null || c.isClosed() || !c.moveToLast()) return null;
		final long account_id = c.getLong(mIndices.account_id);
		final long status_id = c.getLong(mIndices.status_id);
		return findStatusInDatabases(mContext, account_id, status_id);
	}

	@Override
	public long getLastStatusId() {
		final Cursor c = getCursor();
		if (c == null || c.isClosed() || !c.moveToLast()) return -1;
		return c.getLong(mIndices.status_id);
	}

	@Override
	public ParcelableStatus getStatus(final int position) {
		final Cursor c = getCursor();
		if (c == null || c.isClosed() || !c.moveToPosition(position)) return null;
		return new ParcelableStatus(c, mIndices);
	}

	@Override
	public long getStatusId(final int position) {
		final Cursor c = getCursor();
		if (c == null || c.isClosed() || !c.moveToPosition(position)) return -1;
		return c.getLong(mIndices.status_id);
	}

	@Override
	public View getView(final int position, final View convertView, final ViewGroup parent) {
		final View view = super.getView(position, convertView, parent);
		final Object tag = view.getTag();
		// animate the item
		if (tag instanceof StatusViewHolder && position > mMaxAnimationPosition) {
			if (mAnimationEnabled) {
				view.startAnimation(((StatusViewHolder) tag).item_animation);
			}
			mMaxAnimationPosition = position;
		}
		return view;
	}

	@Override
	public boolean isLastItemFiltered() {
		return mFiltersEnabled && mIsLastItemFiltered;
	}

	@Override
	public View newView(final Context context, final Cursor cursor, final ViewGroup parent) {
		final View view = super.newView(context, cursor, parent);
		final Object tag = view.getTag();
		if (!(tag instanceof StatusViewHolder)) {
			final StatusViewHolder holder = new StatusViewHolder(view);
			holder.profile_image.setOnClickListener(this);
			holder.my_profile_image.setOnClickListener(this);
			holder.image_preview.setOnClickListener(this);
			holder.content.setOnOverflowIconClickListener(this);
			view.setTag(holder);
		}
		return view;
	}

	@Override
	public void onClick(final View view) {
		if (mMultiSelectManager.isActive()) return;
		final Object tag = view.getTag();
		final int position = tag instanceof Integer ? (Integer) tag : -1;
		if (position == -1) return;
		switch (view.getId()) {
			case R.id.image_preview: {
				final ParcelableStatus status = getStatus(position);
				if (status == null || status.media_link == null) return;
				openImage(mContext, status.media_link, status.is_possibly_sensitive);
				break;
			}
			case R.id.my_profile_image:
			case R.id.profile_image: {
				final ParcelableStatus status = getStatus(position);
				if (status == null) return;
				if (mContext instanceof Activity) {
					openUserProfile((Activity) mContext, status.account_id, status.user_id, status.user_screen_name);
				}
				break;
			}
		}
	}

	@Override
	public void onOverflowIconClick(final View view) {
		if (mMultiSelectManager.isActive()) return;
		final Object tag = view.getTag();
		if (tag instanceof StatusViewHolder) {
			final StatusViewHolder holder = (StatusViewHolder) tag;
			final int position = holder.position;
			if (position == -1 || mListener == null) return;
			mListener.onMenuButtonClick(view, position, getItemId(position));
		}
	}

	@Override
	public void setAnimationEnabled(final boolean anim) {
		if (mAnimationEnabled == anim) return;
		mAnimationEnabled = anim;
	}

	@Override
	public void setData(final Cursor data) {
		swapCursor(data);
	}

	@Override
	public void setDisplayImagePreview(final boolean display) {
		if (display == mDisplayImagePreview) return;
		mDisplayImagePreview = display;
		notifyDataSetChanged();
	}

	@Override
	public void setDisplaySensitiveContents(final boolean display) {
		if (display == mDisplaySensitiveContents) return;
		mDisplaySensitiveContents = display;
		notifyDataSetChanged();
	}

	@Override
	public void setFavoritesHightlightDisabled(final boolean disable) {
		if (disable == mFavoritesHighlightDisabled) return;
		mFavoritesHighlightDisabled = disable;
		notifyDataSetChanged();
	}

	@Override
	public void setFiltersEnabled(final boolean enabled) {
		if (mFiltersEnabled == enabled) return;
		mFiltersEnabled = enabled;
		rebuildFilterInfo();
		notifyDataSetChanged();
	}

	@Override
	public void setGapDisallowed(final boolean disallowed) {
		if (mGapDisallowed == disallowed) return;
		mGapDisallowed = disallowed;
		notifyDataSetChanged();
	}

	@Override
	public void setIgnoredFilterFields(final boolean user, final boolean text_plain, final boolean text_html,
			final boolean source, final boolean retweeted_by_id) {
		mFilterIgnoreTextPlain = text_plain;
		mFilterIgnoreTextHtml = text_html;
		mFilterIgnoreUser = user;
		mFilterIgnoreSource = source;
		rebuildFilterInfo();
		notifyDataSetChanged();
	}

	@Override
	public void setIndicateMyStatusDisabled(final boolean disable) {
		if (mIndicateMyStatusDisabled == disable) return;
		mIndicateMyStatusDisabled = disable;
		notifyDataSetChanged();
	}

	@Override
	public void setMaxAnimationPosition(final int position) {
		mMaxAnimationPosition = position;
	}

	@Override
	public void setMentionsHightlightDisabled(final boolean disable) {
		if (disable == mMentionsHighlightDisabled) return;
		mMentionsHighlightDisabled = disable;
		notifyDataSetChanged();
	}

	@Override
	public void setMenuButtonClickListener(final MenuButtonClickListener listener) {
		mListener = listener;
	}

	@Override
	public Cursor swapCursor(final Cursor cursor) {
		mIndices = cursor != null ? new CursorStatusIndices(cursor) : null;
		rebuildFilterInfo();
		return super.swapCursor(cursor);
	}

	private void rebuildFilterInfo() {
		final Cursor c = getCursor();
		if (mIndices != null && c != null && !c.isClosed() && c.getCount() > 0 && c.moveToLast()) {
			final long userId = mFilterIgnoreUser ? -1 : c.getLong(mIndices.user_id);
			final String textPlain = mFilterIgnoreTextPlain ? null : c.getString(mIndices.text_plain);
			final String textHtml = mFilterIgnoreTextHtml ? null : c.getString(mIndices.text_html);
			final String source = mFilterIgnoreSource ? null : c.getString(mIndices.source);
			final long retweetedById = mFilterRetweetedById ? -1 : c.getLong(mIndices.retweeted_by_user_id);
			mIsLastItemFiltered = isFiltered(mDatabase, userId, textPlain, textHtml, source, retweetedById);
		} else {
			mIsLastItemFiltered = false;
		}
	}

	private static int getItemResource(final boolean compactCards) {
		return compactCards ? R.layout.card_item_status_compact : R.layout.card_item_status;
	}
}
