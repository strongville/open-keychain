/*
 * Copyright (C) 2017 Vincent Breitmoser <v.breitmoser@mugenguild.com>
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

package org.sufficientlysecure.keychain.ui.keyview.loader;


import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import android.content.ContentResolver;
import android.content.Context;
import android.database.Cursor;
import android.support.annotation.Nullable;
import android.support.v4.content.AsyncTaskLoader;
import android.util.Log;

import com.google.auto.value.AutoValue;
import org.sufficientlysecure.keychain.Constants;
import org.sufficientlysecure.keychain.linked.LinkedAttribute;
import org.sufficientlysecure.keychain.linked.UriAttribute;
import org.sufficientlysecure.keychain.provider.KeychainContract.KeyRings;
import org.sufficientlysecure.keychain.provider.KeychainContract.UserPackets;
import org.sufficientlysecure.keychain.ui.keyview.loader.IdentityLoader.IdentityInfo;


public class IdentityLoader extends AsyncTaskLoader<List<IdentityInfo>> {
    private static final String[] USER_PACKETS_PROJECTION = new String[]{
            UserPackets._ID,
            UserPackets.TYPE,
            UserPackets.USER_ID,
            UserPackets.ATTRIBUTE_DATA,
            UserPackets.RANK,
            UserPackets.VERIFIED,
            UserPackets.IS_PRIMARY,
            UserPackets.IS_REVOKED,
            UserPackets.NAME,
            UserPackets.EMAIL,
            UserPackets.COMMENT,
    };
    private static final int INDEX_ID = 0;
    private static final int INDEX_TYPE = 1;
    private static final int INDEX_USER_ID = 2;
    private static final int INDEX_ATTRIBUTE_DATA = 3;
    private static final int INDEX_RANK = 4;
    private static final int INDEX_VERIFIED = 5;
    private static final int INDEX_IS_PRIMARY = 6;
    private static final int INDEX_IS_REVOKED = 7;
    private static final int INDEX_NAME = 8;
    private static final int INDEX_EMAIL = 9;
    private static final int INDEX_COMMENT = 10;

    private static final String USER_IDS_WHERE = UserPackets.IS_REVOKED + " = 0";

    private final ContentResolver contentResolver;
    private final long masterKeyId;
    private final boolean showLinkedIds;

    private List<IdentityInfo> cachedResult;

    private ForceLoadContentObserver identityObserver;

    public IdentityLoader(Context context, ContentResolver contentResolver, long masterKeyId, boolean showLinkedIds) {
        super(context);

        this.contentResolver = contentResolver;
        this.masterKeyId = masterKeyId;
        this.showLinkedIds = showLinkedIds;

        this.identityObserver = new ForceLoadContentObserver();
    }

    @Override
    public List<IdentityInfo> loadInBackground() {
        ArrayList<IdentityInfo> identities = new ArrayList<>();

        if (showLinkedIds) {
            loadLinkedIds(identities);
        }
        loadUserIds(identities);

        return Collections.unmodifiableList(identities);
    }

    private void loadLinkedIds(ArrayList<IdentityInfo> identities) {
        Cursor cursor = contentResolver.query(UserPackets.buildLinkedIdsUri(masterKeyId),
                USER_PACKETS_PROJECTION, USER_IDS_WHERE, null, null);
        if (cursor == null) {
            Log.e(Constants.TAG, "Error loading key items!");
            return;
        }

        try {
            while (cursor.moveToNext()) {
                int rank = cursor.getInt(INDEX_RANK);
                int verified = cursor.getInt(INDEX_VERIFIED);
                boolean isPrimary = cursor.getInt(INDEX_IS_PRIMARY) != 0;

                byte[] data = cursor.getBlob(INDEX_ATTRIBUTE_DATA);
                try {
                    UriAttribute uriAttribute = LinkedAttribute.fromAttributeData(data);
                    if (uriAttribute instanceof LinkedAttribute) {
                        LinkedIdInfo identityInfo = LinkedIdInfo.create(rank, verified, isPrimary, uriAttribute);
                        identities.add(identityInfo);
                    }
                } catch (IOException e) {
                    Log.e(Constants.TAG, "Failed parsing uri attribute", e);
                }
            }
        } finally {
            cursor.close();
        }
    }

    private void loadUserIds(ArrayList<IdentityInfo> identities) {
        Cursor cursor = contentResolver.query(UserPackets.buildUserIdsUri(masterKeyId),
                USER_PACKETS_PROJECTION, USER_IDS_WHERE, null, null);
        if (cursor == null) {
            Log.e(Constants.TAG, "Error loading key items!");
            return;
        }

        try {
            while (cursor.moveToNext()) {
                int rank = cursor.getInt(INDEX_RANK);
                int verified = cursor.getInt(INDEX_VERIFIED);
                boolean isPrimary = cursor.getInt(INDEX_IS_PRIMARY) != 0;

                if (!cursor.isNull(INDEX_NAME) || !cursor.isNull(INDEX_EMAIL)) {
                    String name = cursor.getString(INDEX_NAME);
                    String email = cursor.getString(INDEX_EMAIL);
                    String comment = cursor.getString(INDEX_COMMENT);

                    IdentityInfo identityInfo = UserIdInfo.create(rank, verified, isPrimary, name, email, comment);
                    identities.add(identityInfo);
                }
            }
        } finally {
            cursor.close();
        }
    }

    @Override
    public void deliverResult(List<IdentityInfo> keySubkeyStatus) {
        cachedResult = keySubkeyStatus;

        if (isStarted()) {
            super.deliverResult(keySubkeyStatus);
        }
    }

    @Override
    protected void onStartLoading() {
        if (cachedResult != null) {
            deliverResult(cachedResult);
        }

        if (takeContentChanged() || cachedResult == null) {
            forceLoad();
        }

        getContext().getContentResolver().registerContentObserver(
                KeyRings.buildGenericKeyRingUri(masterKeyId), true, identityObserver);
    }

    @Override
    protected void onAbandon() {
        super.onAbandon();

        getContext().getContentResolver().unregisterContentObserver(identityObserver);
    }

    public interface IdentityInfo {
        int getRank();
        int getVerified();
        boolean isPrimary();
    }

    @AutoValue
    public abstract static class UserIdInfo implements IdentityInfo {
        public abstract int getRank();
        public abstract int getVerified();
        public abstract boolean isPrimary();

        @Nullable
        public abstract String getName();
        @Nullable
        public abstract String getEmail();
        @Nullable
        public abstract String getComment();

        static UserIdInfo create(int rank, int verified, boolean isPrimary, String name, String email,
                String comment) {
            return new AutoValue_IdentityLoader_UserIdInfo(rank, verified, isPrimary, name, email, comment);
        }
    }

    @AutoValue
    public abstract static class LinkedIdInfo implements IdentityInfo {
        public abstract int getRank();
        public abstract int getVerified();
        public abstract boolean isPrimary();

        public abstract UriAttribute getUriAttribute();

        static LinkedIdInfo create(int rank, int verified, boolean isPrimary, UriAttribute uriAttribute) {
            return new AutoValue_IdentityLoader_LinkedIdInfo(rank, verified, isPrimary, uriAttribute);
        }
    }
}
