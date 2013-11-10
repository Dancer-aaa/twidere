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

package org.mariotaku.twidere.util;

import static org.mariotaku.twidere.provider.TweetStore.STATUSES_URIS;
import static org.mariotaku.twidere.util.ContentResolverUtils.bulkDelete;
import static org.mariotaku.twidere.util.ContentResolverUtils.bulkInsert;
import static org.mariotaku.twidere.util.Utils.appendQueryParameters;
import static org.mariotaku.twidere.util.Utils.getActivatedAccountIds;
import static org.mariotaku.twidere.util.Utils.getAllStatusesIds;
import static org.mariotaku.twidere.util.Utils.getDefaultAccountId;
import static org.mariotaku.twidere.util.Utils.getNewestMessageIdsFromDatabase;
import static org.mariotaku.twidere.util.Utils.getNewestStatusIdsFromDatabase;
import static org.mariotaku.twidere.util.Utils.getStatusIdsInDatabase;
import static org.mariotaku.twidere.util.Utils.getTwitterInstance;
import static org.mariotaku.twidere.util.Utils.getUserName;
import static org.mariotaku.twidere.util.Utils.makeDirectMessageContentValues;
import static org.mariotaku.twidere.util.Utils.makeStatusContentValues;
import static org.mariotaku.twidere.util.Utils.makeTrendsContentValues;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;

import edu.ucdavis.earlybird.ProfilingUtil;

import org.mariotaku.querybuilder.Columns.Column;
import org.mariotaku.querybuilder.RawItemArray;
import org.mariotaku.querybuilder.Where;
import org.mariotaku.twidere.R;
import org.mariotaku.twidere.app.TwidereApplication;
import org.mariotaku.twidere.model.ListResponse;
import org.mariotaku.twidere.model.ParcelableLocation;
import org.mariotaku.twidere.model.ParcelableStatus;
import org.mariotaku.twidere.model.ParcelableStatusUpdate;
import org.mariotaku.twidere.model.ParcelableUser;
import org.mariotaku.twidere.model.ParcelableUserList;
import org.mariotaku.twidere.model.SingleResponse;
import org.mariotaku.twidere.preference.HomeRefreshContentPreference;
import org.mariotaku.twidere.provider.TweetStore;
import org.mariotaku.twidere.provider.TweetStore.CachedHashtags;
import org.mariotaku.twidere.provider.TweetStore.CachedTrends;
import org.mariotaku.twidere.provider.TweetStore.CachedUsers;
import org.mariotaku.twidere.provider.TweetStore.DirectMessages;
import org.mariotaku.twidere.provider.TweetStore.Mentions;
import org.mariotaku.twidere.provider.TweetStore.Notifications;
import org.mariotaku.twidere.provider.TweetStore.Statuses;
import org.mariotaku.twidere.service.UpdateStatusService;
import org.mariotaku.twidere.task.AsyncTask;
import org.mariotaku.twidere.task.CacheUsersStatusesTask;
import org.mariotaku.twidere.task.ManagedAsyncTask;

import twitter4j.DirectMessage;
import twitter4j.Paging;
import twitter4j.ResponseList;
import twitter4j.Trends;
import twitter4j.Twitter;
import twitter4j.TwitterException;
import twitter4j.User;
import twitter4j.UserList;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class AsyncTwitterWrapper extends TwitterWrapper {

	private static AsyncTwitterWrapper sInstance;

	private final Context mContext;
	private final AsyncTaskManager mAsyncTaskManager;
	private final SharedPreferences mPreferences;
	private final MessagesManager mMessagesManager;
	private final ContentResolver mResolver;

	private final boolean mLargeProfileImage;

	private int mGetHomeTimelineTaskId, mGetMentionsTaskId;
	private int mGetReceivedDirectMessagesTaskId, mGetSentDirectMessagesTaskId;
	private int mGetLocalTrendsTaskId;

	public AsyncTwitterWrapper(final Context context) {
		mContext = context;
		final TwidereApplication app = TwidereApplication.getInstance(context);
		mAsyncTaskManager = app.getAsyncTaskManager();
		mMessagesManager = app.getMessagesManager();
		mPreferences = context.getSharedPreferences(SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
		mResolver = context.getContentResolver();
		mLargeProfileImage = context.getResources().getBoolean(R.bool.hires_profile_image);
	}

	public int addUserListMembersAsync(final long account_id, final int list_id, final ParcelableUser... users) {
		final AddUserListMembersTask task = new AddUserListMembersTask(account_id, list_id, users);
		return mAsyncTaskManager.add(task, true);
	}

	public int clearNotification(final int id) {
		final Uri uri = Notifications.CONTENT_URI.buildUpon().appendPath(String.valueOf(id)).build();
		return mResolver.delete(uri, null, null);
	}

	public void clearUnreadCountAsync(final int position) {
		final ClearUnreadCountTask task = new ClearUnreadCountTask(position);
		task.execute();
	}

	public int createBlockAsync(final long account_id, final long user_id) {
		final CreateBlockTask task = new CreateBlockTask(account_id, user_id);
		return mAsyncTaskManager.add(task, true);
	}

	public int createFavoriteAsync(final long account_id, final long status_id) {
		final CreateFavoriteTask task = new CreateFavoriteTask(account_id, status_id);
		return mAsyncTaskManager.add(task, true);
	}

	public int createFriendshipAsync(final long account_id, final long user_id) {
		final CreateFriendshipTask task = new CreateFriendshipTask(account_id, user_id);
		return mAsyncTaskManager.add(task, true);
	}

	public int createMultiBlockAsync(final long account_id, final long[] user_ids) {
		final CreateMultiBlockTask task = new CreateMultiBlockTask(account_id, user_ids);
		return mAsyncTaskManager.add(task, true);
	}

	public int createUserListAsync(final long account_id, final String list_name, final boolean is_public,
			final String description) {
		final CreateUserListTask task = new CreateUserListTask(account_id, list_name, is_public, description);
		return mAsyncTaskManager.add(task, true);
	}

	public int createUserListSubscriptionAsync(final long account_id, final int list_id) {
		final CreateUserListSubscriptionTask task = new CreateUserListSubscriptionTask(account_id, list_id);
		return mAsyncTaskManager.add(task, true);
	}

	public int deleteUserListMembersAsync(final long account_id, final int list_id, final long... user_ids) {
		final DeleteUserListMembersTask task = new DeleteUserListMembersTask(account_id, list_id, user_ids, null);
		return mAsyncTaskManager.add(task, true);
	}

	public int deleteUserListMembersAsync(final long account_id, final int list_id, final String... screen_names) {
		final DeleteUserListMembersTask task = new DeleteUserListMembersTask(account_id, list_id, null, screen_names);
		return mAsyncTaskManager.add(task, true);
	}

	public int destroyBlockAsync(final long account_id, final long user_id) {
		final DestroyBlockTask task = new DestroyBlockTask(account_id, user_id);
		return mAsyncTaskManager.add(task, true);
	}

	public int destroyDirectMessageAsync(final long account_id, final long message_id) {
		final DestroyDirectMessageTask task = new DestroyDirectMessageTask(account_id, message_id);
		return mAsyncTaskManager.add(task, true);
	}

	public int destroyFavoriteAsync(final long account_id, final long status_id) {
		final DestroyFavoriteTask task = new DestroyFavoriteTask(account_id, status_id);
		return mAsyncTaskManager.add(task, true);
	}

	public int destroyFriendshipAsync(final long account_id, final long user_id) {
		final DestroyFriendshipTask task = new DestroyFriendshipTask(account_id, user_id);
		return mAsyncTaskManager.add(task, true);
	}

	public int destroyStatusAsync(final long account_id, final long status_id) {
		final DestroyStatusTask task = new DestroyStatusTask(account_id, status_id);
		return mAsyncTaskManager.add(task, true);
	}

	public int destroyUserListAsync(final long account_id, final int list_id) {
		final DestroyUserListTask task = new DestroyUserListTask(account_id, list_id);
		return mAsyncTaskManager.add(task, true);
	}

	public int destroyUserListSubscriptionAsync(final long account_id, final int list_id) {
		final DestroyUserListSubscriptionTask task = new DestroyUserListSubscriptionTask(account_id, list_id);
		return mAsyncTaskManager.add(task, true);
	}

	public int getHomeTimelineAsync(final long[] account_ids, final long[] max_ids, final long[] since_ids) {
		mAsyncTaskManager.cancel(mGetHomeTimelineTaskId);
		final GetHomeTimelineTask task = new GetHomeTimelineTask(account_ids, max_ids, since_ids);
		return mGetHomeTimelineTaskId = mAsyncTaskManager.add(task, true);
	}

	public int getLocalTrendsAsync(final long account_id, final int woeid) {
		mAsyncTaskManager.cancel(mGetLocalTrendsTaskId);
		final GetLocalTrendsTask task = new GetLocalTrendsTask(account_id, woeid);
		return mGetLocalTrendsTaskId = mAsyncTaskManager.add(task, true);
	}

	public int getMentionsAsync(final long[] account_ids, final long[] max_ids, final long[] since_ids) {
		mAsyncTaskManager.cancel(mGetMentionsTaskId);
		final GetMentionsTask task = new GetMentionsTask(account_ids, max_ids, since_ids);
		return mGetMentionsTaskId = mAsyncTaskManager.add(task, true);
	}

	public int getReceivedDirectMessagesAsync(final long[] account_ids, final long[] max_ids, final long[] since_ids) {
		mAsyncTaskManager.cancel(mGetReceivedDirectMessagesTaskId);
		final GetReceivedDirectMessagesTask task = new GetReceivedDirectMessagesTask(account_ids, max_ids, since_ids);
		return mGetReceivedDirectMessagesTaskId = mAsyncTaskManager.add(task, true);
	}

	public int getSentDirectMessagesAsync(final long[] account_ids, final long[] max_ids, final long[] since_ids) {
		mAsyncTaskManager.cancel(mGetSentDirectMessagesTaskId);
		final GetSentDirectMessagesTask task = new GetSentDirectMessagesTask(account_ids, max_ids, since_ids);
		return mGetSentDirectMessagesTaskId = mAsyncTaskManager.add(task, true);
	}

	public boolean hasActivatedTask() {
		return mAsyncTaskManager.hasRunningTask();
	}

	public boolean isCreatingFriendship(final long account_id, final long user_id) {
		for (final ManagedAsyncTask<?, ?, ?> task : mAsyncTaskManager.getTaskSpecList()) {
			if (task instanceof CreateFriendshipTask) {
				final CreateFriendshipTask create_friendship = (CreateFriendshipTask) task;
				if (create_friendship.getStatus() == AsyncTask.Status.RUNNING
						&& create_friendship.getAccountId() == account_id && create_friendship.getUserId() == user_id)
					return true;
			}
		}
		return false;
	}

	public boolean isDestroyingFriendship(final long account_id, final long user_id) {
		for (final ManagedAsyncTask<?, ?, ?> task : mAsyncTaskManager.getTaskSpecList()) {
			if (task instanceof DestroyFriendshipTask) {
				final DestroyFriendshipTask create_friendship = (DestroyFriendshipTask) task;
				if (create_friendship.getStatus() == AsyncTask.Status.RUNNING
						&& create_friendship.getAccountId() == account_id && create_friendship.getUserId() == user_id)
					return true;
			}
		}
		return false;
	}

	public boolean isHomeTimelineRefreshing() {
		return mAsyncTaskManager.hasRunningTasksForTag(TASK_TAG_GET_HOME_TIMELINE)
				|| mAsyncTaskManager.hasRunningTasksForTag(TASK_TAG_STORE_HOME_TIMELINE);
	}

	public boolean isLocalTrendsRefreshing() {
		return mAsyncTaskManager.hasRunningTasksForTag(TASK_TAG_GET_TRENDS)
				|| mAsyncTaskManager.hasRunningTasksForTag(TASK_TAG_STORE_TRENDS);
	}

	public boolean isMentionsRefreshing() {
		return mAsyncTaskManager.hasRunningTasksForTag(TASK_TAG_GET_MENTIONS)
				|| mAsyncTaskManager.hasRunningTasksForTag(TASK_TAG_STORE_MENTIONS);
	}

	public boolean isReceivedDirectMessagesRefreshing() {
		return mAsyncTaskManager.hasRunningTasksForTag(TASK_TAG_GET_RECEIVED_DIRECT_MESSAGES)
				|| mAsyncTaskManager.hasRunningTasksForTag(TASK_TAG_STORE_RECEIVED_DIRECT_MESSAGES);
	}

	public boolean isSentDirectMessagesRefreshing() {
		return mAsyncTaskManager.hasRunningTasksForTag(TASK_TAG_GET_SENT_DIRECT_MESSAGES)
				|| mAsyncTaskManager.hasRunningTasksForTag(TASK_TAG_STORE_SENT_DIRECT_MESSAGES);
	}

	public int refreshAll() {
		final long[] account_ids = getActivatedAccountIds(mContext);
		return refreshAll(account_ids);
	}

	public int refreshAll(final long[] account_ids) {
		if (mPreferences.getBoolean(PREFERENCE_KEY_HOME_REFRESH_MENTIONS,
				HomeRefreshContentPreference.DEFAULT_ENABLE_MENTIONS)) {
			final long[] since_ids = getNewestStatusIdsFromDatabase(mContext, Mentions.CONTENT_URI, account_ids);
			getMentionsAsync(account_ids, null, since_ids);
		}
		if (mPreferences.getBoolean(PREFERENCE_KEY_HOME_REFRESH_DIRECT_MESSAGES,
				HomeRefreshContentPreference.DEFAULT_ENABLE_DIRECT_MESSAGES)) {
			final long[] since_ids = getNewestMessageIdsFromDatabase(mContext, DirectMessages.Inbox.CONTENT_URI,
					account_ids);
			getReceivedDirectMessagesAsync(account_ids, null, since_ids);
			getSentDirectMessagesAsync(account_ids, null, null);
		}
		if (mPreferences.getBoolean(PREFERENCE_KEY_HOME_REFRESH_TRENDS,
				HomeRefreshContentPreference.DEFAULT_ENABLE_TRENDS)) {
			final long account_id = getDefaultAccountId(mContext);
			final int woeid = mPreferences.getInt(PREFERENCE_KEY_LOCAL_TRENDS_WOEID, 1);
			getLocalTrendsAsync(account_id, woeid);
		}
		final long[] since_ids = getNewestStatusIdsFromDatabase(mContext, Statuses.CONTENT_URI, account_ids);
		return getHomeTimelineAsync(account_ids, null, since_ids);
	}

	public void removeUnreadCountsAsync(final int position, final Map<Long, Set<Long>> counts) {
		final RemoveUnreadCountsTask task = new RemoveUnreadCountsTask(position, counts);
		task.execute();
	}

	public int reportMultiSpam(final long account_id, final long[] user_ids) {
		final ReportMultiSpamTask task = new ReportMultiSpamTask(account_id, user_ids);
		return mAsyncTaskManager.add(task, true);
	}

	public int reportSpamAsync(final long account_id, final long user_id) {
		final ReportSpamTask task = new ReportSpamTask(account_id, user_id);
		return mAsyncTaskManager.add(task, true);
	}

	public int retweetStatus(final long account_id, final long status_id) {
		final RetweetStatusTask task = new RetweetStatusTask(account_id, status_id);
		return mAsyncTaskManager.add(task, true);
	}

	public int sendDirectMessage(final long account_id, final String screen_name, final long user_id,
			final String message) {
		final SendDirectMessageTask task = new SendDirectMessageTask(account_id, screen_name, user_id, message);
		return mAsyncTaskManager.add(task, true);
	}

	public int updateProfile(final long account_id, final String name, final String url, final String location,
			final String description) {
		final UpdateProfileTask task = new UpdateProfileTask(mContext, mAsyncTaskManager, account_id, name, url,
				location, description);
		return mAsyncTaskManager.add(task, true);
	}

	public int updateProfileBannerImage(final long account_id, final Uri image_uri, final boolean delete_image) {
		final UpdateProfileBannerImageTask task = new UpdateProfileBannerImageTask(mContext, mAsyncTaskManager,
				account_id, image_uri, delete_image);
		return mAsyncTaskManager.add(task, true);
	}

	public int updateProfileImage(final long account_id, final Uri image_uri, final boolean delete_image) {
		final UpdateProfileImageTask task = new UpdateProfileImageTask(mContext, mAsyncTaskManager, account_id,
				image_uri, delete_image);
		return mAsyncTaskManager.add(task, true);
	}

	public int updateStatusAsync(final long[] account_ids, final String content, final ParcelableLocation location,
			final Uri image_uri, final int image_type, final long in_reply_to, final boolean is_possibly_sensitive) {
		final Intent intent = new Intent(mContext, UpdateStatusService.class);
		intent.putExtra(EXTRA_STATUS, new ParcelableStatusUpdate(account_ids, content, location, image_uri, image_type,
				in_reply_to, is_possibly_sensitive));
		mContext.startService(intent);
		return 0;
	}

	public int updateStatusesAsync(final ParcelableStatusUpdate... statuses) {
		final Intent intent = new Intent(mContext, UpdateStatusService.class);
		intent.putExtra(EXTRA_STATUSES, statuses);
		mContext.startService(intent);
		return 0;
	}

	public int updateUserListDetails(final long account_id, final int list_id, final boolean is_public,
			final String name, final String description) {
		final UpdateUserListDetailsTask task = new UpdateUserListDetailsTask(account_id, list_id, is_public, name,
				description);
		return mAsyncTaskManager.add(task, true);
	}

	public static AsyncTwitterWrapper getInstance(final Context context) {
		if (sInstance != null) return sInstance;
		return sInstance = new AsyncTwitterWrapper(context);
	}

	public static class UpdateProfileBannerImageTask extends ManagedAsyncTask<Void, Void, SingleResponse<Boolean>> {

		private final long account_id;
		private final Uri image_uri;
		private final boolean delete_image;
		private final Context mContext;

		public UpdateProfileBannerImageTask(final Context context, final AsyncTaskManager manager,
				final long account_id, final Uri image_uri, final boolean delete_image) {
			super(context, manager);
			mContext = context;
			this.account_id = account_id;
			this.image_uri = image_uri;
			this.delete_image = delete_image;
		}

		@Override
		protected SingleResponse<Boolean> doInBackground(final Void... params) {
			return TwitterWrapper.updateProfileBannerImage(mContext, account_id, image_uri, delete_image);
		}

		@Override
		protected void onPostExecute(final SingleResponse<Boolean> result) {
			if (result != null && result.data != null && result.data) {
				Utils.showOkMessage(mContext, R.string.profile_banner_image_updated, false);
			} else {
				Utils.showErrorMessage(mContext, R.string.action_updating_profile_banner_image, result.exception, true);
			}
			final Intent intent = new Intent(BROADCAST_PROFILE_BANNER_UPDATED);
			intent.putExtra(EXTRA_USER_ID, account_id);
			intent.putExtra(EXTRA_SUCCEED, result != null && result.data != null);
			mContext.sendBroadcast(intent);
			super.onPostExecute(result);
		}

	}

	public static class UpdateProfileImageTask extends ManagedAsyncTask<Void, Void, SingleResponse<ParcelableUser>> {

		private final long account_id;
		private final Uri image_uri;
		private final boolean delete_image;
		private final Context context;

		public UpdateProfileImageTask(final Context context, final AsyncTaskManager manager, final long account_id,
				final Uri image_uri, final boolean delete_image) {
			super(context, manager);
			this.context = context;
			this.account_id = account_id;
			this.image_uri = image_uri;
			this.delete_image = delete_image;
		}

		@Override
		protected SingleResponse<ParcelableUser> doInBackground(final Void... params) {
			return TwitterWrapper.updateProfileImage(context, account_id, image_uri, delete_image);
		}

		@Override
		protected void onPostExecute(final SingleResponse<ParcelableUser> result) {
			if (result != null && result.data != null) {
				Utils.showOkMessage(context, R.string.profile_image_updated, false);
			} else {
				Utils.showErrorMessage(context, R.string.action_updating_profile_image, result.exception, true);
			}
			final Intent intent = new Intent(BROADCAST_PROFILE_UPDATED);
			intent.putExtra(EXTRA_USER_ID, account_id);
			intent.putExtra(EXTRA_SUCCEED, result != null && result.data != null);
			context.sendBroadcast(intent);
			super.onPostExecute(result);
		}

	}

	public static class UpdateProfileTask extends ManagedAsyncTask<Void, Void, SingleResponse<ParcelableUser>> {

		private final long account_id;
		private final String name, url, location, description;
		private final Context context;

		public UpdateProfileTask(final Context context, final AsyncTaskManager manager, final long account_id,
				final String name, final String url, final String location, final String description) {
			super(context, manager);
			this.context = context;
			this.account_id = account_id;
			this.name = name;
			this.url = url;
			this.location = location;
			this.description = description;
		}

		@Override
		protected SingleResponse<ParcelableUser> doInBackground(final Void... params) {
			return updateProfile(context, account_id, name, url, location, description);
		}

		@Override
		protected void onPostExecute(final SingleResponse<ParcelableUser> result) {
			if (result != null && result.data != null) {
				Utils.showOkMessage(context, R.string.profile_updated, false);
			} else {
				Utils.showErrorMessage(context, context.getString(R.string.action_updating_profile), result.exception,
						true);
			}
			final Intent intent = new Intent(BROADCAST_PROFILE_IMAGE_UPDATED);
			intent.putExtra(EXTRA_USER_ID, account_id);
			intent.putExtra(EXTRA_SUCCEED, result != null && result.data != null);
			context.sendBroadcast(intent);
			super.onPostExecute(result);
		}

	}

	class AddUserListMembersTask extends ManagedAsyncTask<Void, Void, SingleResponse<ParcelableUserList>> {

		private final long account_id;
		private final int list_id;
		private final ParcelableUser[] users;

		public AddUserListMembersTask(final long account_id, final int list_id, final ParcelableUser[] users) {
			super(mContext, mAsyncTaskManager);
			this.account_id = account_id;
			this.list_id = list_id;
			this.users = users;
		}

		@Override
		protected SingleResponse<ParcelableUserList> doInBackground(final Void... params) {
			final Twitter twitter = getTwitterInstance(mContext, account_id, false);
			if (twitter != null && users != null) {
				try {
					final long[] user_ids = new long[users.length];
					for (int i = 0, j = users.length; i < j; i++) {
						user_ids[i] = users[i].id;
					}
					final ParcelableUserList list = new ParcelableUserList(
							twitter.addUserListMembers(list_id, user_ids), account_id, false);
					return SingleResponse.newInstance(list, null);
				} catch (final TwitterException e) {
					return SingleResponse.newInstance(null, e);
				}
			}
			return SingleResponse.nullInstance();
		}

		@Override
		protected void onPostExecute(final SingleResponse<ParcelableUserList> result) {
			final boolean succeed = result != null && result.data != null && result.data.id > 0;
			if (succeed) {
				final String message = mContext.getString(R.string.added_users_to_list, result.data.name);
				mMessagesManager.showOkMessage(message, false);
			} else {
				mMessagesManager.showErrorMessage(R.string.action_adding_member, result.exception, true);
			}
			final Intent intent = new Intent(BROADCAST_USER_LIST_MEMBERS_ADDED);
			intent.putExtra(EXTRA_USER_LIST, result.data);
			intent.putExtra(EXTRA_USERS, users);
			intent.putExtra(EXTRA_SUCCEED, succeed);
			mContext.sendBroadcast(intent);
			super.onPostExecute(result);
		}

	}

	final class ClearUnreadCountTask extends AsyncTask<Void, Void, Integer> {
		private final int position;

		ClearUnreadCountTask(final int position) {
			this.position = position;
		}

		@Override
		protected Integer doInBackground(final Void... params) {
			return clearUnreadCount(mContext, position);
		}

	}

	class CreateBlockTask extends ManagedAsyncTask<Void, Void, SingleResponse<User>> {

		private final long account_id, user_id;

		public CreateBlockTask(final long account_id, final long user_id) {
			super(mContext, mAsyncTaskManager);
			this.account_id = account_id;
			this.user_id = user_id;
		}

		@Override
		protected SingleResponse<User> doInBackground(final Void... params) {

			final Twitter twitter = getTwitterInstance(mContext, account_id, false);
			if (twitter != null) {
				try {
					final User user = twitter.createBlock(user_id);
					for (final Uri uri : STATUSES_URIS) {
						final String where = Statuses.ACCOUNT_ID + " = " + account_id + " AND " + Statuses.USER_ID
								+ " = " + user_id;
						mResolver.delete(uri, where, null);

					}
					// I bet you don't want to see this user in your auto
					// complete
					// list.
					final String where = CachedUsers.USER_ID + " = " + user_id;
					mResolver.delete(CachedUsers.CONTENT_URI, where, null);
					return SingleResponse.newInstance(user, null);
				} catch (final TwitterException e) {
					return SingleResponse.newInstance(null, e);
				}
			}
			return SingleResponse.nullInstance();
		}

		@Override
		protected void onPostExecute(final SingleResponse<User> result) {
			if (result != null && result.data != null && result.data.getId() > 0) {
				final String message = mContext.getString(R.string.blocked_user, getUserName(mContext, result.data));
				mMessagesManager.showInfoMessage(message, false);
			} else {
				mMessagesManager.showErrorMessage(R.string.action_blocking, result.exception, true);
			}
			final Intent intent = new Intent(BROADCAST_BLOCKSTATE_CHANGED);
			intent.putExtra(EXTRA_USER_ID, user_id);
			intent.putExtra(EXTRA_SUCCEED, result != null && result.data != null);
			mContext.sendBroadcast(intent);
			super.onPostExecute(result);
		}

	}

	class CreateFavoriteTask extends ManagedAsyncTask<Void, Void, SingleResponse<ParcelableStatus>> {

		private final long account_id, status_id;

		public CreateFavoriteTask(final long account_id, final long status_id) {
			super(mContext, mAsyncTaskManager);
			this.account_id = account_id;
			this.status_id = status_id;
		}

		@Override
		protected SingleResponse<ParcelableStatus> doInBackground(final Void... params) {

			if (account_id < 0) return SingleResponse.nullInstance();

			final Twitter twitter = getTwitterInstance(mContext, account_id, false);
			if (twitter != null) {
				try {
					final twitter4j.Status status = twitter.createFavorite(status_id);
					final ContentValues values = new ContentValues();
					values.put(Statuses.IS_FAVORITE, true);
					final StringBuilder where = new StringBuilder();
					where.append(Statuses.ACCOUNT_ID + " = " + account_id);
					where.append(" AND ");
					where.append("(");
					where.append(Statuses.STATUS_ID + " = " + status_id);
					where.append(" OR ");
					where.append(Statuses.RETWEET_ID + " = " + status_id);
					where.append(")");
					for (final Uri uri : TweetStore.STATUSES_URIS) {
						mResolver.update(uri, values, where.toString(), null);
					}
					return SingleResponse.dataOnly(new ParcelableStatus(status, account_id, false, mLargeProfileImage));
				} catch (final TwitterException e) {
					return SingleResponse.exceptionOnly(e);
				}
			}
			return SingleResponse.nullInstance();
		}

		@Override
		protected void onPostExecute(final SingleResponse<ParcelableStatus> result) {

			if (result.data != null) {
				final Intent intent = new Intent(BROADCAST_FAVORITE_CHANGED);
				intent.putExtra(EXTRA_STATUS, result.data);
				intent.putExtra(EXTRA_FAVORITED, true);
				mContext.sendBroadcast(intent);
				mMessagesManager.showOkMessage(R.string.status_favorited, false);
			} else {
				mMessagesManager.showErrorMessage(R.string.action_favoriting, result.exception, true);
			}
			super.onPostExecute(result);
		}

	}

	class CreateFriendshipTask extends ManagedAsyncTask<Void, Void, SingleResponse<User>> {

		private final long account_id;
		private final long user_id;

		public CreateFriendshipTask(final long account_id, final long user_id) {
			super(mContext, mAsyncTaskManager);
			this.account_id = account_id;
			this.user_id = user_id;
		}

		public long getAccountId() {
			return account_id;
		}

		public long getUserId() {
			return user_id;
		}

		@Override
		protected SingleResponse<User> doInBackground(final Void... params) {

			final Twitter twitter = getTwitterInstance(mContext, account_id, false);
			if (twitter != null) {
				try {
					final User user = twitter.createFriendship(user_id);
					return SingleResponse.newInstance(user, null);
				} catch (final TwitterException e) {
					return SingleResponse.newInstance(null, e);
				}
			}
			return SingleResponse.nullInstance();
		}

		@Override
		protected void onPostExecute(final SingleResponse<User> result) {
			if (result != null && result.data != null) {
				final String message = mContext.getString(R.string.followed_user, getUserName(mContext, result.data));
				mMessagesManager.showOkMessage(message, false);
			} else {
				mMessagesManager.showErrorMessage(R.string.action_following, result.exception, false);
			}
			final Intent intent = new Intent(BROADCAST_FRIENDSHIP_CHANGED);
			intent.putExtra(EXTRA_USER_ID, user_id);
			intent.putExtra(EXTRA_SUCCEED, result != null && result.data != null);
			mContext.sendBroadcast(intent);
			super.onPostExecute(result);
		}

	}

	class CreateMultiBlockTask extends ManagedAsyncTask<Void, Void, ListResponse<Long>> {

		private final long account_id;
		private final long[] user_ids;

		public CreateMultiBlockTask(final long account_id, final long[] user_ids) {
			super(mContext, mAsyncTaskManager);
			this.account_id = account_id;
			this.user_ids = user_ids;
		}

		@Override
		protected ListResponse<Long> doInBackground(final Void... params) {
			final List<Long> blocked_users = new ArrayList<Long>();
			final Twitter twitter = getTwitterInstance(mContext, account_id, false);
			if (twitter != null) {
				for (final long user_id : user_ids) {
					try {
						final User user = twitter.createBlock(user_id);
						if (user == null || user.getId() <= 0) {
							continue;
						}
						blocked_users.add(user.getId());
					} catch (final TwitterException e) {
						deleteCaches(blocked_users);
						return new ListResponse<Long>(null, e, null);
					}
				}
			}
			deleteCaches(blocked_users);
			return new ListResponse<Long>(blocked_users, null, null);
		}

		@Override
		protected void onPostExecute(final ListResponse<Long> result) {
			if (result.list != null) {
				mMessagesManager.showInfoMessage(R.string.users_blocked, false);
			} else {
				mMessagesManager.showErrorMessage(R.string.action_blocking, result.exception, true);
			}
			final Intent intent = new Intent(BROADCAST_MULTI_BLOCKSTATE_CHANGED);
			intent.putExtra(EXTRA_USER_ID, user_ids);
			intent.putExtra(EXTRA_SUCCEED, result.list != null);
			mContext.sendBroadcast(intent);
			super.onPostExecute(result);
		}

		private void deleteCaches(final List<Long> list) {
			for (final Uri uri : STATUSES_URIS) {
				bulkDelete(mResolver, uri, Statuses.USER_ID, list, Statuses.ACCOUNT_ID + " = " + account_id, false);
			}
			// I bet you don't want to see these users in your auto complete
			// list.
			bulkDelete(mResolver, CachedUsers.CONTENT_URI, CachedUsers.USER_ID, list, null, false);
		}
	}

	class CreateUserListSubscriptionTask extends ManagedAsyncTask<Void, Void, SingleResponse<ParcelableUserList>> {

		private final long account_id;
		private final int list_id;

		public CreateUserListSubscriptionTask(final long account_id, final int list_id) {
			super(mContext, mAsyncTaskManager);
			this.account_id = account_id;
			this.list_id = list_id;
		}

		@Override
		protected SingleResponse<ParcelableUserList> doInBackground(final Void... params) {
			final Twitter twitter = getTwitterInstance(mContext, account_id, false);
			if (twitter != null) {
				try {
					final ParcelableUserList list = new ParcelableUserList(twitter.createUserListSubscription(list_id),
							account_id, false);
					return new SingleResponse<ParcelableUserList>(list, null);
				} catch (final TwitterException e) {
					return SingleResponse.exceptionOnly(e);
				}
			}
			return SingleResponse.nullInstance();
		}

		@Override
		protected void onPostExecute(final SingleResponse<ParcelableUserList> result) {
			final boolean succeed = result != null && result.data != null;
			if (succeed) {
				final String message = mContext.getString(R.string.subscribed_to_list, result.data.name);
				mMessagesManager.showOkMessage(message, false);
			} else {
				mMessagesManager.showErrorMessage(R.string.action_subscribing_to_list, result.exception, true);
			}
			final Intent intent = new Intent(BROADCAST_USER_LIST_SUBSCRIBED);
			intent.putExtra(EXTRA_USER_LIST, result.data);
			intent.putExtra(EXTRA_SUCCEED, succeed);
			mContext.sendBroadcast(intent);
			super.onPostExecute(result);
		}

	}

	class CreateUserListTask extends ManagedAsyncTask<Void, Void, SingleResponse<UserList>> {

		private final long account_id;
		private final String list_name, description;
		private final boolean is_public;

		public CreateUserListTask(final long account_id, final String list_name, final boolean is_public,
				final String description) {
			super(mContext, mAsyncTaskManager);
			this.account_id = account_id;
			this.list_name = list_name;
			this.description = description;
			this.is_public = is_public;
		}

		@Override
		protected SingleResponse<UserList> doInBackground(final Void... params) {

			final Twitter twitter = getTwitterInstance(mContext, account_id, false);
			if (twitter != null) {
				try {
					if (list_name != null) {
						final UserList list = twitter.createUserList(list_name, is_public, description);
						return SingleResponse.newInstance(list, null);
					}
				} catch (final TwitterException e) {
					return SingleResponse.newInstance(null, e);
				}
			}
			return SingleResponse.nullInstance();
		}

		@Override
		protected void onPostExecute(final SingleResponse<UserList> result) {
			final boolean succeed = result != null && result.data != null && result.data.getId() > 0;
			if (succeed) {
				final String message = mContext.getString(R.string.created_list, result.data.getName());
				mMessagesManager.showOkMessage(message, false);
			} else {
				mMessagesManager.showErrorMessage(R.string.action_creating_list, result.exception, true);
			}
			final Intent intent = new Intent(BROADCAST_USER_LIST_CREATED);
			intent.putExtra(EXTRA_SUCCEED, succeed);
			mContext.sendBroadcast(intent);
			super.onPostExecute(result);
		}

	}

	class DeleteUserListMembersTask extends ManagedAsyncTask<Void, Void, SingleResponse<ParcelableUserList>> {

		private final long account_id;
		private final int list_id;
		private final long[] user_ids;
		private final String[] screen_names;

		public DeleteUserListMembersTask(final long account_id, final int list_id, final long[] user_ids,
				final String[] screen_names) {
			super(mContext, mAsyncTaskManager);
			this.account_id = account_id;
			this.list_id = list_id;
			this.user_ids = user_ids;
			this.screen_names = screen_names;
		}

		@Override
		protected SingleResponse<ParcelableUserList> doInBackground(final Void... params) {
			final Twitter twitter = getTwitterInstance(mContext, account_id, false);
			if (twitter != null) {
				try {
					final ParcelableUserList list;
					if (user_ids != null) {
						list = new ParcelableUserList(twitter.deleteUserListMembers(list_id, user_ids), account_id,
								false);
					} else if (screen_names != null) {
						list = new ParcelableUserList(twitter.deleteUserListMembers(list_id, screen_names), account_id,
								false);
					} else
						return SingleResponse.nullInstance();
					return new SingleResponse<ParcelableUserList>(list, null);
				} catch (final TwitterException e) {
					return SingleResponse.exceptionOnly(e);
				}
			}
			return SingleResponse.nullInstance();
		}

		@Override
		protected void onPostExecute(final SingleResponse<ParcelableUserList> result) {
			final boolean succeed = result != null && result.data != null && result.data.id > 0;
			if (succeed) {
				final String message = mContext.getString(R.string.deleted_users_from_list, result.data.name);
				mMessagesManager.showInfoMessage(message, false);
			} else {
				mMessagesManager.showErrorMessage(R.string.action_deleting, result.exception, true);
			}
			final Intent intent = new Intent(BROADCAST_USER_LIST_MEMBERS_DELETED);
			intent.putExtra(EXTRA_USER_LIST, result.data);
			intent.putExtra(EXTRA_USER_IDS, user_ids);
			intent.putExtra(EXTRA_SCREEN_NAMES, screen_names);
			intent.putExtra(EXTRA_SUCCEED, succeed);
			mContext.sendBroadcast(intent);
			super.onPostExecute(result);
		}

	}

	class DestroyBlockTask extends ManagedAsyncTask<Void, Void, SingleResponse<User>> {

		private final long account_id;
		private final long user_id;

		public DestroyBlockTask(final long account_id, final long user_id) {
			super(mContext, mAsyncTaskManager);
			this.account_id = account_id;
			this.user_id = user_id;
		}

		@Override
		protected SingleResponse<User> doInBackground(final Void... params) {

			final Twitter twitter = getTwitterInstance(mContext, account_id, false);
			if (twitter != null) {
				try {
					final User user = twitter.destroyBlock(user_id);
					return SingleResponse.newInstance(user, null);
				} catch (final TwitterException e) {
					return SingleResponse.newInstance(null, e);
				}
			}
			return SingleResponse.nullInstance();
		}

		@Override
		protected void onPostExecute(final SingleResponse<User> result) {
			if (result != null && result.data != null) {
				final String message = mContext.getString(R.string.unblocked_user, getUserName(mContext, result.data));
				mMessagesManager.showInfoMessage(message, false);
			} else {
				mMessagesManager.showErrorMessage(R.string.action_unblocking, result.exception, true);
			}
			final Intent intent = new Intent(BROADCAST_BLOCKSTATE_CHANGED);
			intent.putExtra(EXTRA_USER_ID, user_id);
			intent.putExtra(EXTRA_SUCCEED, result != null && result.data != null);
			mContext.sendBroadcast(intent);
			super.onPostExecute(result);
		}

	}

	class DestroyDirectMessageTask extends ManagedAsyncTask<Void, Void, SingleResponse<DirectMessage>> {

		private final long message_id;
		private final long account_id;

		public DestroyDirectMessageTask(final long account_id, final long message_id) {
			super(mContext, mAsyncTaskManager);

			this.account_id = account_id;
			this.message_id = message_id;
		}

		@Override
		protected SingleResponse<DirectMessage> doInBackground(final Void... args) {
			final Twitter twitter = getTwitterInstance(mContext, account_id, false);
			if (twitter == null) return SingleResponse.nullInstance();
			try {
				final DirectMessage message = twitter.destroyDirectMessage(message_id);
				deleteMessages(message_id);
				return SingleResponse.newInstance(message, null);
			} catch (final TwitterException e) {
				if (e.getErrorCode() == 34) {
					deleteMessages(message_id);
				}
				return SingleResponse.newInstance(null, e);
			}
		}

		@Override
		protected void onPostExecute(final SingleResponse<DirectMessage> result) {
			super.onPostExecute(result);
			if (result == null) return;
			if (result.data != null && result.data.getId() > 0 || result.exception instanceof TwitterException
					&& ((TwitterException) result.exception).getErrorCode() == 34) {
				mMessagesManager.showInfoMessage(R.string.direct_message_deleted, false);
			} else {
				mMessagesManager.showErrorMessage(R.string.action_deleting, result.exception, true);
			}
		}

		private void deleteMessages(final long message_id) {
			final String where = DirectMessages.MESSAGE_ID + " = " + message_id;
			mResolver.delete(DirectMessages.Inbox.CONTENT_URI, where, null);
			mResolver.delete(DirectMessages.Outbox.CONTENT_URI, where, null);
		}
	}

	class DestroyFavoriteTask extends ManagedAsyncTask<Void, Void, SingleResponse<ParcelableStatus>> {

		private final long account_id;

		private final long status_id;

		public DestroyFavoriteTask(final long account_id, final long status_id) {
			super(mContext, mAsyncTaskManager);
			this.account_id = account_id;
			this.status_id = status_id;
		}

		@Override
		protected SingleResponse<ParcelableStatus> doInBackground(final Void... params) {
			if (account_id < 0) return SingleResponse.nullInstance();
			final Twitter twitter = getTwitterInstance(mContext, account_id, false);
			if (twitter != null) {
				try {
					final twitter4j.Status status = twitter.destroyFavorite(status_id);
					final ContentValues values = new ContentValues();
					values.put(Statuses.IS_FAVORITE, 0);
					final StringBuilder where = new StringBuilder();
					where.append(Statuses.ACCOUNT_ID + " = " + account_id);
					where.append(" AND ");
					where.append("(");
					where.append(Statuses.STATUS_ID + " = " + status_id);
					where.append(" OR ");
					where.append(Statuses.RETWEET_ID + " = " + status_id);
					where.append(")");
					for (final Uri uri : TweetStore.STATUSES_URIS) {
						mResolver.update(uri, values, where.toString(), null);
					}
					return new SingleResponse<ParcelableStatus>(new ParcelableStatus(status, account_id, false,
							mLargeProfileImage), null);
				} catch (final TwitterException e) {
					return SingleResponse.exceptionOnly(e);
				}
			}
			return SingleResponse.nullInstance();
		}

		@Override
		protected void onPostExecute(final SingleResponse<ParcelableStatus> result) {
			if (result.data != null) {
				final Intent intent = new Intent(BROADCAST_FAVORITE_CHANGED);
				intent.putExtra(EXTRA_STATUS, result.data);
				intent.putExtra(EXTRA_FAVORITED, false);
				mContext.sendBroadcast(intent);
				mMessagesManager.showInfoMessage(R.string.status_unfavorited, false);
			} else {
				mMessagesManager.showErrorMessage(R.string.action_unfavoriting, result.exception, true);
			}
			super.onPostExecute(result);
		}

	}

	class DestroyFriendshipTask extends ManagedAsyncTask<Void, Void, SingleResponse<User>> {

		private final long account_id;
		private final long user_id;

		public DestroyFriendshipTask(final long account_id, final long user_id) {
			super(mContext, mAsyncTaskManager);
			this.account_id = account_id;
			this.user_id = user_id;
		}

		public long getAccountId() {
			return account_id;
		}

		public long getUserId() {
			return user_id;
		}

		@Override
		protected SingleResponse<User> doInBackground(final Void... params) {

			final Twitter twitter = getTwitterInstance(mContext, account_id, false);
			if (twitter != null) {
				try {
					final User user = twitter.destroyFriendship(user_id);
					final String where = Statuses.ACCOUNT_ID + " = " + account_id + " AND " + Statuses.USER_ID + " = "
							+ user_id;
					mResolver.delete(Statuses.CONTENT_URI, where, null);
					return SingleResponse.newInstance(user, null);
				} catch (final TwitterException e) {
					return SingleResponse.newInstance(null, e);
				}
			}
			return SingleResponse.nullInstance();
		}

		@Override
		protected void onPostExecute(final SingleResponse<User> result) {
			if (result != null && result.data != null) {
				final String message = mContext.getString(R.string.unfollowed_user, getUserName(mContext, result.data));
				mMessagesManager.showInfoMessage(message, false);
			} else {
				mMessagesManager.showErrorMessage(R.string.action_unfollowing, result.exception, true);
			}
			final Intent intent = new Intent(BROADCAST_FRIENDSHIP_CHANGED);
			intent.putExtra(EXTRA_USER_ID, user_id);
			intent.putExtra(EXTRA_SUCCEED, result != null && result.data != null);
			mContext.sendBroadcast(intent);
			super.onPostExecute(result);
		}

	}

	class DestroyStatusTask extends ManagedAsyncTask<Void, Void, SingleResponse<twitter4j.Status>> {

		private final long account_id;

		private final long status_id;

		public DestroyStatusTask(final long account_id, final long status_id) {
			super(mContext, mAsyncTaskManager);
			this.account_id = account_id;
			this.status_id = status_id;
		}

		@Override
		protected SingleResponse<twitter4j.Status> doInBackground(final Void... params) {

			final Twitter twitter = getTwitterInstance(mContext, account_id, false);
			if (twitter != null) {
				try {
					final twitter4j.Status status = twitter.destroyStatus(status_id);
					final ContentValues values = new ContentValues();
					values.put(Statuses.MY_RETWEET_ID, -1);
					for (final Uri uri : TweetStore.STATUSES_URIS) {
						mResolver.delete(uri, Statuses.STATUS_ID + " = " + status_id, null);
						mResolver.update(uri, values, Statuses.MY_RETWEET_ID + " = " + status_id, null);
					}
					return SingleResponse.newInstance(status, null);
				} catch (final TwitterException e) {
					return SingleResponse.newInstance(null, e);
				}
			}
			return SingleResponse.nullInstance();
		}

		@Override
		protected void onPostExecute(final SingleResponse<twitter4j.Status> result) {
			final Intent intent = new Intent(BROADCAST_STATUS_DESTROYED);
			if (result != null && result.data != null && result.data.getId() > 0) {
				intent.putExtra(EXTRA_STATUS_ID, status_id);
				intent.putExtra(EXTRA_SUCCEED, true);
				if (result.data.getRetweetedStatus() != null) {
					mMessagesManager.showInfoMessage(R.string.retweet_cancelled, false);
				} else {
					mMessagesManager.showInfoMessage(R.string.status_deleted, false);
				}
			} else {
				mMessagesManager.showErrorMessage(R.string.action_deleting, result.exception, true);
			}
			mContext.sendBroadcast(intent);
			super.onPostExecute(result);
		}

	}

	class DestroyUserListSubscriptionTask extends ManagedAsyncTask<Void, Void, SingleResponse<ParcelableUserList>> {

		private final long account_id;
		private final int list_id;

		public DestroyUserListSubscriptionTask(final long account_id, final int list_id) {
			super(mContext, mAsyncTaskManager);
			this.account_id = account_id;
			this.list_id = list_id;
		}

		@Override
		protected SingleResponse<ParcelableUserList> doInBackground(final Void... params) {

			final Twitter twitter = getTwitterInstance(mContext, account_id, false);
			if (twitter != null) {
				try {
					final ParcelableUserList list = new ParcelableUserList(
							twitter.destroyUserListSubscription(list_id), account_id, false);
					return SingleResponse.newInstance(list, null);
				} catch (final TwitterException e) {
					return SingleResponse.newInstance(null, e);
				}
			}
			return SingleResponse.nullInstance();
		}

		@Override
		protected void onPostExecute(final SingleResponse<ParcelableUserList> result) {
			final boolean succeed = result.data != null;
			if (succeed) {
				final String message = mContext.getString(R.string.unsubscribed_from_list, result.data.name);
				mMessagesManager.showOkMessage(message, false);
			} else {
				mMessagesManager.showErrorMessage(R.string.action_unsubscribing_from_list, result.exception, true);
			}
			final Intent intent = new Intent(BROADCAST_USER_LIST_UNSUBSCRIBED);
			intent.putExtra(EXTRA_USER_LIST, result.data);
			intent.putExtra(EXTRA_SUCCEED, succeed);
			mContext.sendBroadcast(intent);
			super.onPostExecute(result);
		}

	}

	class DestroyUserListTask extends ManagedAsyncTask<Void, Void, SingleResponse<ParcelableUserList>> {

		private final long account_id;
		private final int list_id;

		public DestroyUserListTask(final long account_id, final int list_id) {
			super(mContext, mAsyncTaskManager);
			this.account_id = account_id;
			this.list_id = list_id;
		}

		@Override
		protected SingleResponse<ParcelableUserList> doInBackground(final Void... params) {

			final Twitter twitter = getTwitterInstance(mContext, account_id, false);
			if (twitter != null) {
				try {
					if (list_id > 0) {
						final ParcelableUserList list = new ParcelableUserList(twitter.destroyUserList(list_id),
								account_id, false);
						return new SingleResponse<ParcelableUserList>(list, null);
					}
				} catch (final TwitterException e) {
					return SingleResponse.exceptionOnly(e);
				}
			}
			return SingleResponse.nullInstance();
		}

		@Override
		protected void onPostExecute(final SingleResponse<ParcelableUserList> result) {
			final boolean succeed = result.data != null;
			if (succeed) {
				final String message = mContext.getString(R.string.deleted_list, result.data.name);
				mMessagesManager.showInfoMessage(message, false);
			} else {
				mMessagesManager.showErrorMessage(R.string.action_deleting, result.exception, true);
			}
			final Intent intent = new Intent(BROADCAST_USER_LIST_DELETED);
			intent.putExtra(EXTRA_SUCCEED, succeed);
			intent.putExtra(EXTRA_USER_LIST, result.data);
			mContext.sendBroadcast(intent);
			super.onPostExecute(result);
		}

	}

	abstract class GetDirectMessagesTask extends ManagedAsyncTask<Void, Void, List<TwitterListResponse<DirectMessage>>> {

		private final long[] account_ids, max_ids, since_ids;

		public GetDirectMessagesTask(final long[] account_ids, final long[] max_ids, final long[] since_ids,
				final String tag) {
			super(mContext, mAsyncTaskManager, tag);
			this.account_ids = account_ids;
			this.max_ids = max_ids;
			this.since_ids = since_ids;
		}

		public abstract ResponseList<DirectMessage> getDirectMessages(Twitter twitter, Paging paging)
				throws TwitterException;

		@Override
		protected List<TwitterListResponse<DirectMessage>> doInBackground(final Void... params) {

			final List<TwitterListResponse<DirectMessage>> result = new ArrayList<TwitterListResponse<DirectMessage>>();

			if (account_ids == null) return result;

			int idx = 0;
			final int load_item_limit = mPreferences.getInt(PREFERENCE_KEY_LOAD_ITEM_LIMIT,
					PREFERENCE_DEFAULT_LOAD_ITEM_LIMIT);
			for (final long account_id : account_ids) {
				final Twitter twitter = getTwitterInstance(mContext, account_id, true);
				if (twitter != null) {
					try {
						final Paging paging = new Paging();
						paging.setCount(load_item_limit);
						long max_id = -1, since_id = -1;
						if (isMaxIdsValid() && max_ids[idx] > 0) {
							max_id = max_ids[idx];
							paging.setMaxId(max_id);
						}
						if (isSinceIdsValid() && since_ids[idx] > 0) {
							since_id = since_ids[idx];
							paging.setSinceId(since_id);
						}
						final ResponseList<DirectMessage> statuses = getDirectMessages(twitter, paging);

						if (statuses != null) {
							result.add(new TwitterListResponse<DirectMessage>(account_id, max_id, since_id, statuses,
									null));
						}
					} catch (final TwitterException e) {
						result.add(new TwitterListResponse<DirectMessage>(account_id, e));
					}
				}
				idx++;
			}
			return result;

		}

		@Override
		protected void onPostExecute(final List<TwitterListResponse<DirectMessage>> result) {
			super.onPostExecute(result);
			for (final TwitterListResponse<DirectMessage> response : result) {
				if (response.list == null) {
					mMessagesManager.showErrorMessage(R.string.action_refreshing_direct_messages, response.exception,
							true);
				}
			}
		}

		final boolean isMaxIdsValid() {
			return max_ids != null && max_ids.length == account_ids.length;
		}

		final boolean isSinceIdsValid() {
			return since_ids != null && since_ids.length == account_ids.length;
		}

	}

	class GetHomeTimelineTask extends GetStatusesTask {

		public GetHomeTimelineTask(final long[] account_ids, final long[] max_ids, final long[] since_ids) {
			super(account_ids, max_ids, since_ids, TASK_TAG_GET_HOME_TIMELINE);
		}

		@Override
		public ResponseList<twitter4j.Status> getStatuses(final Twitter twitter, final Paging paging)
				throws TwitterException {
			return twitter.getHomeTimeline(paging);
		}

		@Override
		protected void onPostExecute(final List<StatusListResponse> responses) {
			super.onPostExecute(responses);
			mAsyncTaskManager.add(new StoreHomeTimelineTask(responses, !isMaxIdsValid()), true);
			mGetHomeTimelineTaskId = -1;
			for (final StatusListResponse response : responses) {
				if (response.list == null) {
					mMessagesManager.showErrorMessage(R.string.action_refreshing_home_timeline, response.exception,
							true);
					break;
				}
			}
		}

		@Override
		protected void onPreExecute() {
			final Intent intent = new Intent(BROADCAST_RESCHEDULE_HOME_TIMELINE_REFRESHING);
			mContext.sendBroadcast(intent);
			super.onPreExecute();
		}

	}

	class GetLocalTrendsTask extends GetTrendsTask {

		private final int woeid;

		public GetLocalTrendsTask(final long account_id, final int woeid) {
			super(account_id);
			this.woeid = woeid;
		}

		@Override
		public List<Trends> getTrends(final Twitter twitter) throws TwitterException {
			final ArrayList<Trends> trends_list = new ArrayList<Trends>();
			if (twitter != null) {
				trends_list.add(twitter.getLocationTrends(woeid));
			}
			return trends_list;
		}

		@Override
		protected void onPostExecute(final ListResponse<Trends> result) {
			mAsyncTaskManager.add(new StoreLocalTrendsTask(result), true);
			super.onPostExecute(result);

		}

	}

	class GetMentionsTask extends GetStatusesTask {

		public GetMentionsTask(final long[] account_ids, final long[] max_ids, final long[] since_ids) {
			super(account_ids, max_ids, since_ids, TASK_TAG_GET_MENTIONS);
		}

		@Override
		public ResponseList<twitter4j.Status> getStatuses(final Twitter twitter, final Paging paging)
				throws TwitterException {
			return twitter.getMentionsTimeline(paging);
		}

		@Override
		protected void onPostExecute(final List<StatusListResponse> responses) {
			super.onPostExecute(responses);
			mAsyncTaskManager.add(new StoreMentionsTask(responses, !isMaxIdsValid()), true);
			mGetMentionsTaskId = -1;
			for (final StatusListResponse response : responses) {
				if (response.list == null) {
					mMessagesManager.showErrorMessage(R.string.action_refreshing_mentions, response.exception, true);
					break;
				}
			}
		}

		@Override
		protected void onPreExecute() {

			final Intent intent = new Intent(BROADCAST_RESCHEDULE_MENTIONS_REFRESHING);
			mContext.sendBroadcast(intent);
			super.onPreExecute();
		}

	}

	class GetReceivedDirectMessagesTask extends GetDirectMessagesTask {

		public GetReceivedDirectMessagesTask(final long[] account_ids, final long[] max_ids, final long[] since_ids) {
			super(account_ids, max_ids, since_ids, TASK_TAG_GET_RECEIVED_DIRECT_MESSAGES);
		}

		@Override
		public ResponseList<DirectMessage> getDirectMessages(final Twitter twitter, final Paging paging)
				throws TwitterException {
			return twitter.getDirectMessages(paging);
		}

		@Override
		protected void onPostExecute(final List<TwitterListResponse<DirectMessage>> responses) {
			super.onPostExecute(responses);
			mAsyncTaskManager.add(new StoreReceivedDirectMessagesTask(responses, !isMaxIdsValid()), true);
			mGetReceivedDirectMessagesTaskId = -1;
		}

		@Override
		protected void onPreExecute() {
			final Intent intent = new Intent(BROADCAST_RESCHEDULE_DIRECT_MESSAGES_REFRESHING);
			mContext.sendBroadcast(intent);
			super.onPreExecute();
		}

	}

	class GetSentDirectMessagesTask extends GetDirectMessagesTask {

		public GetSentDirectMessagesTask(final long[] account_ids, final long[] max_ids, final long[] since_ids) {
			super(account_ids, max_ids, since_ids, TASK_TAG_GET_SENT_DIRECT_MESSAGES);
		}

		@Override
		public ResponseList<DirectMessage> getDirectMessages(final Twitter twitter, final Paging paging)
				throws TwitterException {
			return twitter.getSentDirectMessages(paging);
		}

		@Override
		protected void onPostExecute(final List<TwitterListResponse<DirectMessage>> responses) {
			super.onPostExecute(responses);
			mAsyncTaskManager.add(new StoreSentDirectMessagesTask(responses, !isMaxIdsValid()), true);
			mGetSentDirectMessagesTaskId = -1;
		}

	}

	abstract class GetStatusesTask extends ManagedAsyncTask<Void, Void, List<StatusListResponse>> {

		private final long[] account_ids, max_ids, since_ids;

		public GetStatusesTask(final long[] account_ids, final long[] max_ids, final long[] since_ids, final String tag) {
			super(mContext, mAsyncTaskManager, tag);
			this.account_ids = account_ids;
			this.max_ids = max_ids;
			this.since_ids = since_ids;
		}

		public abstract ResponseList<twitter4j.Status> getStatuses(Twitter twitter, Paging paging)
				throws TwitterException;

		@Override
		protected List<StatusListResponse> doInBackground(final Void... params) {

			final List<StatusListResponse> result = new ArrayList<StatusListResponse>();

			if (account_ids == null) return result;

			int idx = 0;
			final int load_item_limit = mPreferences.getInt(PREFERENCE_KEY_LOAD_ITEM_LIMIT,
					PREFERENCE_DEFAULT_LOAD_ITEM_LIMIT);
			for (final long account_id : account_ids) {
				final Twitter twitter = getTwitterInstance(mContext, account_id, true);
				if (twitter != null) {
					try {
						final Paging paging = new Paging();
						paging.setCount(load_item_limit);
						long max_id = -1, since_id = -1;
						if (isMaxIdsValid() && max_ids[idx] > 0) {
							max_id = max_ids[idx];
							paging.setMaxId(max_id);
						}
						if (isSinceIdsValid() && since_ids[idx] > 0) {
							since_id = since_ids[idx];
							// paging.setSinceId(since_id);
						}
						final List<twitter4j.Status> statuses = new ArrayList<twitter4j.Status>();
						final boolean truncated = truncateStatuses(getStatuses(twitter, paging), statuses, since_id);
						result.add(new StatusListResponse(account_id, max_id, since_id, load_item_limit, statuses,
								truncated));
					} catch (final TwitterException e) {
						result.add(new StatusListResponse(account_id, e));
					}
				}
				idx++;
			}
			return result;
		}

		private boolean truncateStatuses(final List<twitter4j.Status> in, final List<twitter4j.Status> out,
				final long since_id) {
			for (final twitter4j.Status status : in) {
				if (since_id > 0 && status.getId() <= since_id) {
					continue;
				}
				out.add(status);
			}
			return in.size() != out.size();
		}

		final boolean isMaxIdsValid() {
			return max_ids != null && max_ids.length == account_ids.length;
		}

		final boolean isSinceIdsValid() {
			return since_ids != null && since_ids.length == account_ids.length;
		}

	}

	abstract class GetTrendsTask extends ManagedAsyncTask<Void, Void, ListResponse<Trends>> {

		private final long account_id;

		public GetTrendsTask(final long account_id) {
			super(mContext, mAsyncTaskManager, TASK_TAG_GET_TRENDS);
			this.account_id = account_id;
		}

		public abstract List<Trends> getTrends(Twitter twitter) throws TwitterException;

		@Override
		protected ListResponse<Trends> doInBackground(final Void... params) {
			final Twitter twitter = getTwitterInstance(mContext, account_id, false);
			final Bundle extras = new Bundle();
			extras.putLong(EXTRA_ACCOUNT_ID, account_id);
			if (twitter != null) {
				try {
					return new ListResponse<Trends>(getTrends(twitter), null, extras);
				} catch (final TwitterException e) {
					return new ListResponse<Trends>(null, e, extras);
				}
			}
			return new ListResponse<Trends>(null, null, extras);
		}

	}

	final class RemoveUnreadCountsTask extends AsyncTask<Void, Void, Integer> {
		private final int position;
		private final Map<Long, Set<Long>> counts;

		RemoveUnreadCountsTask(final int position, final Map<Long, Set<Long>> counts) {
			this.position = position;
			this.counts = counts;
		}

		@Override
		protected Integer doInBackground(final Void... params) {
			return removeUnreadCounts(mContext, position, counts);
		}

	}

	class ReportMultiSpamTask extends ManagedAsyncTask<Void, Void, ListResponse<Long>> {

		private final long account_id;
		private final long[] user_ids;

		public ReportMultiSpamTask(final long account_id, final long[] user_ids) {
			super(mContext, mAsyncTaskManager);
			this.account_id = account_id;
			this.user_ids = user_ids;
		}

		@Override
		protected ListResponse<Long> doInBackground(final Void... params) {

			final Bundle extras = new Bundle();
			extras.putLong(EXTRA_ACCOUNT_ID, account_id);
			final List<Long> reported_users = new ArrayList<Long>();
			final Twitter twitter = getTwitterInstance(mContext, account_id, false);
			if (twitter != null) {
				for (final long user_id : user_ids) {
					try {
						final User user = twitter.reportSpam(user_id);
						if (user == null || user.getId() <= 0) {
							continue;
						}
						reported_users.add(user.getId());
					} catch (final TwitterException e) {
						return new ListResponse<Long>(null, e, extras);
					}
				}
			}
			return new ListResponse<Long>(reported_users, null, extras);
		}

		@Override
		protected void onPostExecute(final ListResponse<Long> result) {
			if (result != null) {
				final String user_id_where = ListUtils.toString(result.list, ',', false);
				for (final Uri uri : STATUSES_URIS) {
					final String where = Statuses.ACCOUNT_ID + " = " + account_id + " AND " + Statuses.USER_ID
							+ " IN (" + user_id_where + ")";
					mResolver.delete(uri, where, null);
				}
				mMessagesManager.showInfoMessage(R.string.reported_users_for_spam, false);
			}
			final Intent intent = new Intent(BROADCAST_MULTI_BLOCKSTATE_CHANGED);
			intent.putExtra(EXTRA_USER_IDS, user_ids);
			intent.putExtra(EXTRA_ACCOUNT_ID, account_id);
			intent.putExtra(EXTRA_SUCCEED, result != null);
			mContext.sendBroadcast(intent);
			super.onPostExecute(result);
		}

	}

	class ReportSpamTask extends ManagedAsyncTask<Void, Void, SingleResponse<User>> {

		private final long account_id;
		private final long user_id;

		public ReportSpamTask(final long account_id, final long user_id) {
			super(mContext, mAsyncTaskManager);
			this.account_id = account_id;
			this.user_id = user_id;
		}

		@Override
		protected SingleResponse<User> doInBackground(final Void... params) {

			final Twitter twitter = getTwitterInstance(mContext, account_id, false);
			if (twitter != null) {
				try {
					final User user = twitter.reportSpam(user_id);
					return SingleResponse.newInstance(user, null);
				} catch (final TwitterException e) {
					return SingleResponse.newInstance(null, e);
				}
			}
			return SingleResponse.nullInstance();
		}

		@Override
		protected void onPostExecute(final SingleResponse<User> result) {
			if (result != null && result.data != null && result.data.getId() > 0) {
				for (final Uri uri : STATUSES_URIS) {
					final String where = Statuses.ACCOUNT_ID + " = " + account_id + " AND " + Statuses.USER_ID + " = "
							+ user_id;
					mResolver.delete(uri, where, null);
				}
				mMessagesManager.showInfoMessage(R.string.reported_user_for_spam, false);
			} else {
				mMessagesManager.showErrorMessage(R.string.action_reporting_for_spam, result.exception, true);
			}
			final Intent intent = new Intent(BROADCAST_BLOCKSTATE_CHANGED);
			intent.putExtra(EXTRA_USER_ID, user_id);
			intent.putExtra(EXTRA_SUCCEED, result != null && result.data != null);
			mContext.sendBroadcast(intent);
			super.onPostExecute(result);
		}

	}

	class RetweetStatusTask extends ManagedAsyncTask<Void, Void, SingleResponse<twitter4j.Status>> {

		private final long account_id;

		private final long status_id;

		public RetweetStatusTask(final long account_id, final long status_id) {
			super(mContext, mAsyncTaskManager);
			this.account_id = account_id;
			this.status_id = status_id;
		}

		@Override
		protected SingleResponse<twitter4j.Status> doInBackground(final Void... params) {

			if (account_id < 0) return SingleResponse.nullInstance();

			final Twitter twitter = getTwitterInstance(mContext, account_id, false);
			if (twitter != null) {
				try {
					final twitter4j.Status status = twitter.retweetStatus(status_id);
					return SingleResponse.newInstance(status, null);
				} catch (final TwitterException e) {
					return SingleResponse.newInstance(null, e);
				}
			}
			return SingleResponse.nullInstance();
		}

		@Override
		protected void onPostExecute(final SingleResponse<twitter4j.Status> result) {

			if (result.data != null && result.data.getId() > 0) {
				final ContentValues values = new ContentValues();
				values.put(Statuses.MY_RETWEET_ID, result.data.getId());
				final String where = Statuses.STATUS_ID + " = " + status_id + " OR " + Statuses.RETWEET_ID + " = "
						+ status_id;
				for (final Uri uri : STATUSES_URIS) {
					mResolver.update(uri, values, where, null);
				}
				final Intent intent = new Intent(BROADCAST_RETWEET_CHANGED);
				intent.putExtra(EXTRA_STATUS_ID, status_id);
				intent.putExtra(EXTRA_RETWEETED, true);
				mContext.sendBroadcast(intent);
				mMessagesManager.showOkMessage(R.string.status_retweeted, false);
			} else {
				mMessagesManager.showErrorMessage(R.string.action_retweeting, result.exception, true);
			}

			super.onPostExecute(result);
		}

	}

	class SendDirectMessageTask extends ManagedAsyncTask<Void, Void, SingleResponse<DirectMessage>> {

		private final long user_id;
		private final String screen_name;
		private final String message;
		private final long account_id;

		public SendDirectMessageTask(final long account_id, final String screen_name, final long user_id,
				final String message) {
			super(mContext, mAsyncTaskManager);
			this.account_id = account_id;
			this.user_id = user_id;
			this.screen_name = screen_name;
			this.message = message;
		}

		@Override
		protected SingleResponse<DirectMessage> doInBackground(final Void... args) {
			final Twitter twitter = getTwitterInstance(mContext, account_id, true, true);
			if (twitter == null) return SingleResponse.nullInstance();
			try {
				if (user_id > 0)
					return SingleResponse.newInstance(twitter.sendDirectMessage(user_id, message), null);
				else if (screen_name != null)
					return SingleResponse.newInstance(twitter.sendDirectMessage(screen_name, message), null);
			} catch (final TwitterException e) {
				return SingleResponse.newInstance(null, e);
			}
			return SingleResponse.nullInstance();
		}

		@Override
		protected void onPostExecute(final SingleResponse<DirectMessage> result) {
			super.onPostExecute(result);
			if (result.data != null && result.data.getId() > 0) {
				final ContentValues values = makeDirectMessageContentValues(result.data, account_id, true,
						mLargeProfileImage);
				final String delete_where = DirectMessages.ACCOUNT_ID + " = " + account_id + " AND "
						+ DirectMessages.MESSAGE_ID + " = " + result.data.getId();
				mResolver.delete(DirectMessages.Outbox.CONTENT_URI, delete_where, null);
				mResolver.insert(DirectMessages.Outbox.CONTENT_URI, values);
				mMessagesManager.showOkMessage(R.string.direct_message_sent, false);
			} else {
				mMessagesManager.showErrorMessage(R.string.action_sending_direct_message, result.exception, true);
			}
		}

	}

	abstract class StoreDirectMessagesTask extends ManagedAsyncTask<Void, Void, SingleResponse<Bundle>> {

		private final List<TwitterListResponse<DirectMessage>> responses;
		private final Uri uri;

		public StoreDirectMessagesTask(final List<TwitterListResponse<DirectMessage>> result, final Uri uri,
				final boolean notify, final String tag) {
			super(mContext, mAsyncTaskManager, tag);
			responses = result;
			this.uri = uri.buildUpon().appendQueryParameter(QUERY_PARAM_NOTIFY, String.valueOf(notify)).build();
		}

		@Override
		protected SingleResponse<Bundle> doInBackground(final Void... args) {

			boolean succeed = false;
			for (final TwitterListResponse<DirectMessage> response : responses) {
				final long account_id = response.account_id;
				final List<DirectMessage> messages = response.list;
				if (messages != null) {
					final ContentValues[] values_array = new ContentValues[messages.size()];
					final long[] message_ids = new long[messages.size()];

					for (int i = 0, j = messages.size(); i < j; i++) {
						final DirectMessage message = messages.get(i);
						message_ids[i] = message.getId();
						values_array[i] = makeDirectMessageContentValues(message, account_id, isOutgoing(),
								mLargeProfileImage);
					}

					// Delete all rows conflicting before new data inserted.
					{
						final StringBuilder delete_where = new StringBuilder();
						delete_where.append(DirectMessages.ACCOUNT_ID + " = " + account_id);
						delete_where.append(" AND ");
						delete_where.append(Where.in(new Column(DirectMessages.MESSAGE_ID),
								new RawItemArray(message_ids)).getSQL());
						final Uri delete_uri = appendQueryParameters(uri, new NameValuePairImpl(QUERY_PARAM_NOTIFY,
								false));
						mResolver.delete(delete_uri, delete_where.toString(), null);
					}

					// Insert previously fetched items.
					final Uri insert_uri = appendQueryParameters(uri, new NameValuePairImpl(QUERY_PARAM_NOTIFY, false));
					bulkInsert(mResolver, insert_uri, values_array);

				}
				succeed = true;
			}
			final Bundle bundle = new Bundle();
			bundle.putBoolean(EXTRA_SUCCEED, succeed);
			return SingleResponse.newInstance(bundle, null);
		}

		abstract boolean isOutgoing();

	}

	class StoreHomeTimelineTask extends StoreStatusesTask {

		public StoreHomeTimelineTask(final List<StatusListResponse> result, final boolean notify) {
			super(result, Statuses.CONTENT_URI, notify, TASK_TAG_STORE_HOME_TIMELINE);
		}

		@Override
		protected void onPostExecute(final SingleResponse<Bundle> response) {
			final boolean succeed = response != null && response.data != null
					&& response.data.getBoolean(EXTRA_SUCCEED);
			final Bundle extras = new Bundle();
			extras.putBoolean(EXTRA_SUCCEED, succeed);
			mContext.sendBroadcast(new Intent(BROADCAST_HOME_TIMELINE_REFRESHED).putExtras(extras));
			super.onPostExecute(response);
		}

	}

	class StoreLocalTrendsTask extends StoreTrendsTask {

		public StoreLocalTrendsTask(final ListResponse<Trends> result) {
			super(result, CachedTrends.Local.CONTENT_URI);
		}

	}

	class StoreMentionsTask extends StoreStatusesTask {

		public StoreMentionsTask(final List<StatusListResponse> result, final boolean notify) {
			super(result, Mentions.CONTENT_URI, notify, TASK_TAG_STORE_MENTIONS);
		}

		@Override
		protected void onPostExecute(final SingleResponse<Bundle> response) {
			final boolean succeed = response != null && response.data != null
					&& response.data.getBoolean(EXTRA_SUCCEED);
			final Bundle extras = new Bundle();
			extras.putBoolean(EXTRA_SUCCEED, succeed);
			mContext.sendBroadcast(new Intent(BROADCAST_MENTIONS_REFRESHED).putExtras(extras));
			super.onPostExecute(response);
		}

	}

	class StoreReceivedDirectMessagesTask extends StoreDirectMessagesTask {

		public StoreReceivedDirectMessagesTask(final List<TwitterListResponse<DirectMessage>> result,
				final boolean notify) {
			super(result, DirectMessages.Inbox.CONTENT_URI, notify, TASK_TAG_STORE_RECEIVED_DIRECT_MESSAGES);
		}

		@Override
		protected void onPostExecute(final SingleResponse<Bundle> response) {
			final boolean succeed = response != null && response.data != null
					&& response.data.getBoolean(EXTRA_SUCCEED);
			mContext.sendBroadcast(new Intent(BROADCAST_RECEIVED_DIRECT_MESSAGES_REFRESHED).putExtra(EXTRA_SUCCEED,
					succeed));
			super.onPostExecute(response);
		}

		@Override
		boolean isOutgoing() {
			return false;
		}

	}

	class StoreSentDirectMessagesTask extends StoreDirectMessagesTask {

		public StoreSentDirectMessagesTask(final List<TwitterListResponse<DirectMessage>> result, final boolean notify) {
			super(result, DirectMessages.Outbox.CONTENT_URI, notify, TASK_TAG_STORE_SENT_DIRECT_MESSAGES);
		}

		@Override
		protected void onPostExecute(final SingleResponse<Bundle> response) {
			final boolean succeed = response != null && response.data != null
					&& response.data.getBoolean(EXTRA_SUCCEED);
			mContext.sendBroadcast(new Intent(BROADCAST_SENT_DIRECT_MESSAGES_REFRESHED)
					.putExtra(EXTRA_SUCCEED, succeed));
			super.onPostExecute(response);
		}

		@Override
		boolean isOutgoing() {
			return true;
		}

	}

	abstract class StoreStatusesTask extends ManagedAsyncTask<Void, Void, SingleResponse<Bundle>> {

		private final List<StatusListResponse> responses;
		private final Uri uri;
		private final ArrayList<ContentValues> all_statuses = new ArrayList<ContentValues>();

		public StoreStatusesTask(final List<StatusListResponse> result, final Uri uri, final boolean notify,
				final String tag) {
			super(mContext, mAsyncTaskManager, tag);
			responses = result;
			this.uri = uri.buildUpon().appendQueryParameter(QUERY_PARAM_NOTIFY, String.valueOf(notify)).build();
		}

		@Override
		protected SingleResponse<Bundle> doInBackground(final Void... args) {
			boolean succeed = false;
			for (final StatusListResponse response : responses) {
				final long account_id = response.account_id;
				final List<twitter4j.Status> statuses = response.list;
				if (statuses == null || statuses.isEmpty()) {
					continue;
				}
				final ArrayList<Long> ids_in_db = getStatusIdsInDatabase(mContext, uri, account_id);
				final boolean no_items_before = ids_in_db.isEmpty();
				final ContentValues[] values = new ContentValues[statuses.size()];
				final long[] status_ids = new long[statuses.size()];
				for (int i = 0, j = statuses.size(); i < j; i++) {
					final twitter4j.Status status = statuses.get(i);
					values[i] = makeStatusContentValues(status, account_id, mLargeProfileImage);
					status_ids[i] = status.getId();
				}

				// Delete all rows conflicting before new data inserted.
				final StringBuilder delete_where = new StringBuilder();
				delete_where.append(String.format("%s = %d", Statuses.ACCOUNT_ID, account_id));
				delete_where.append(" AND ");
				delete_where.append(Where.in(new Column(Statuses.STATUS_ID), new RawItemArray(status_ids)).getSQL());
				final Uri delete_uri = appendQueryParameters(uri, new NameValuePairImpl(QUERY_PARAM_NOTIFY, false));
				final int rows_deleted = mResolver.delete(delete_uri, delete_where.toString(), null);
				// UCD
				ProfilingUtil.profile(mContext, account_id,
						"Download tweets, " + ArrayUtils.toString(status_ids, ',', true));
				all_statuses.addAll(Arrays.asList(values));
				// Insert previously fetched items.
				final Uri insert_query = appendQueryParameters(uri, new NameValuePairImpl(QUERY_PARAM_NOTIFY, false));
				bulkInsert(mResolver, insert_query, values);

				// Insert a gap.
				final long min_id = status_ids.length != 0 ? ArrayUtils.min(status_ids) : -1;
				final boolean deleted_old_gap = rows_deleted > 0 && ArrayUtils.contains(status_ids, response.max_id);
				final boolean no_rows_deleted = rows_deleted == 0;
				final boolean insert_gap = min_id > 0 && (no_rows_deleted || deleted_old_gap) && !response.truncated
						&& !no_items_before;
				if (insert_gap) {
					final ContentValues gap_value = new ContentValues();
					gap_value.put(Statuses.IS_GAP, 1);
					final StringBuilder where = new StringBuilder();
					where.append(Statuses.ACCOUNT_ID + " = " + account_id);
					where.append(" AND " + Statuses.STATUS_ID + " = " + min_id);
					final Uri update_uri = appendQueryParameters(uri, new NameValuePairImpl(QUERY_PARAM_NOTIFY, false));
					mResolver.update(update_uri, gap_value, where.toString(), null);
				}
				succeed = true;
			}
			final Bundle bundle = new Bundle();
			bundle.putBoolean(EXTRA_SUCCEED, succeed);
			getAllStatusesIds(mContext, uri);
			return SingleResponse.newInstance(bundle, null);
		}

		@Override
		protected void onPreExecute() {
			super.onPreExecute();
			final StatusListResponse[] array = new StatusListResponse[responses.size()];
			new CacheUsersStatusesTask(mContext, responses.toArray(array)).execute();
		}

	}

	class StoreTrendsTask extends ManagedAsyncTask<Void, Void, SingleResponse<Bundle>> {

		private final ListResponse<Trends> response;
		private final Uri uri;

		public StoreTrendsTask(final ListResponse<Trends> result, final Uri uri) {
			super(mContext, mAsyncTaskManager, TASK_TAG_STORE_TRENDS);
			response = result;
			this.uri = uri;
		}

		@Override
		protected SingleResponse<Bundle> doInBackground(final Void... args) {
			final Bundle bundle = new Bundle();
			if (response != null) {

				final List<Trends> messages = response.list;
				final ArrayList<String> hashtags = new ArrayList<String>();
				final ArrayList<ContentValues> hashtag_values = new ArrayList<ContentValues>();
				if (messages != null && messages.size() > 0) {
					final ContentValues[] values_array = makeTrendsContentValues(messages);
					for (final ContentValues values : values_array) {
						final String hashtag = values.getAsString(CachedTrends.NAME).replaceFirst("#", "");
						if (hashtags.contains(hashtag)) {
							continue;
						}
						hashtags.add(hashtag);
						final ContentValues hashtag_value = new ContentValues();
						hashtag_value.put(CachedHashtags.NAME, hashtag);
						hashtag_values.add(hashtag_value);
					}
					mResolver.delete(uri, null, null);
					bulkInsert(mResolver, uri, values_array);
					bulkDelete(mResolver, CachedHashtags.CONTENT_URI, CachedHashtags.NAME, hashtags, null, true);
					bulkInsert(mResolver, CachedHashtags.CONTENT_URI,
							hashtag_values.toArray(new ContentValues[hashtag_values.size()]));
					bundle.putBoolean(EXTRA_SUCCEED, true);
				}
			}
			return new SingleResponse<Bundle>(bundle, null);
		}

		@Override
		protected void onPostExecute(final SingleResponse<Bundle> response) {
			if (response != null && response.data != null && response.data.getBoolean(EXTRA_SUCCEED)) {
				final Intent intent = new Intent(BROADCAST_TRENDS_UPDATED);
				intent.putExtra(EXTRA_SUCCEED, true);
				mContext.sendBroadcast(intent);
			}
			super.onPostExecute(response);

		}

	}

	class UpdateUserListDetailsTask extends ManagedAsyncTask<Void, Void, SingleResponse<ParcelableUserList>> {

		private final long account_id;

		private final int list_id;

		private final boolean is_public;
		private final String name, description;

		public UpdateUserListDetailsTask(final long account_id, final int list_id, final boolean is_public,
				final String name, final String description) {
			super(mContext, mAsyncTaskManager);
			this.account_id = account_id;
			this.name = name;
			this.list_id = list_id;
			this.is_public = is_public;
			this.description = description;
		}

		@Override
		protected SingleResponse<ParcelableUserList> doInBackground(final Void... params) {

			final Twitter twitter = getTwitterInstance(mContext, account_id, false);
			if (twitter != null) {
				try {
					final UserList list = twitter.updateUserList(list_id, name, is_public, description);
					return new SingleResponse<ParcelableUserList>(new ParcelableUserList(list, account_id, false), null);
				} catch (final TwitterException e) {
					return SingleResponse.exceptionOnly(e);
				}
			}
			return SingleResponse.nullInstance();
		}

		@Override
		protected void onPostExecute(final SingleResponse<ParcelableUserList> result) {
			final Intent intent = new Intent(BROADCAST_USER_LIST_DETAILS_UPDATED);
			intent.putExtra(EXTRA_LIST_ID, list_id);
			if (result.data != null && result.data.id > 0) {
				final String message = mContext.getString(R.string.updated_list_details, result.data.name);
				mMessagesManager.showOkMessage(message, false);
				intent.putExtra(EXTRA_SUCCEED, true);
			} else {
				mMessagesManager.showErrorMessage(R.string.action_updating_details, result.exception, true);
			}
			mContext.sendBroadcast(intent);
			super.onPostExecute(result);
		}

	}

}
