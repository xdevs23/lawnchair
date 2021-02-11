/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.launcher3.popup;

import android.app.Activity;
import android.content.ComponentName;
import android.content.ContextWrapper;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.launcher3.dot.DotInfo;
import com.android.launcher3.model.WidgetItem;
import com.android.launcher3.model.data.ItemInfo;
import com.android.launcher3.notification.NotificationKeyData;
import com.android.launcher3.notification.NotificationListener;
import com.android.launcher3.shortcuts.ShortcutRequest;
import com.android.launcher3.util.ComponentKey;
import com.android.launcher3.util.PackageUserKey;
import com.android.launcher3.util.ShortcutUtil;
import com.android.launcher3.widget.WidgetListRowEntry;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Provides data for the popup menu that appears after long-clicking on apps.
 */
public class PopupDataProvider implements NotificationListener.NotificationsChangedListener {

    private static final boolean LOGD = false;
    private static final String TAG = "PopupDataProvider";

    private final Consumer<Predicate<PackageUserKey>> mNotificationDotsChangeListener;

    /** Maps launcher activity components to a count of how many shortcuts they have. */
    private HashMap<ComponentKey, Integer> mDeepShortcutMap = new HashMap<>();
    /** Maps packages to their DotInfo's . */
    private Map<PackageUserKey, DotInfo> mPackageUserToDotInfos = new HashMap<>();
    /** Maps packages to their Widgets */
    private ArrayList<WidgetListRowEntry> mAllWidgets = new ArrayList<>();

    private PopupDataChangeListener mChangeListener = PopupDataChangeListener.INSTANCE;

    public PopupDataProvider(Consumer<Predicate<PackageUserKey>> notificationDotsChangeListener) {
        mNotificationDotsChangeListener = notificationDotsChangeListener;
    }

    private void updateNotificationDots(Predicate<PackageUserKey> updatedDots) {
        mNotificationDotsChangeListener.accept(updatedDots);
        mChangeListener.onNotificationDotsUpdated(updatedDots);
    }

    @Override
    public void onNotificationPosted(PackageUserKey postedPackageUserKey,
            NotificationKeyData notificationKey) {
        DotInfo dotInfo = mPackageUserToDotInfos.get(postedPackageUserKey);
        if (dotInfo == null) {
            dotInfo = new DotInfo();
            mPackageUserToDotInfos.put(postedPackageUserKey, dotInfo);
        }
        if (dotInfo.addOrUpdateNotificationKey(notificationKey)) {
            updateNotificationDots(postedPackageUserKey::equals);
        }
    }

    @Override
    public void onNotificationRemoved(PackageUserKey removedPackageUserKey,
            NotificationKeyData notificationKey) {
        DotInfo oldDotInfo = mPackageUserToDotInfos.get(removedPackageUserKey);
        if (oldDotInfo != null && oldDotInfo.removeNotificationKey(notificationKey)) {
            if (oldDotInfo.getNotificationKeys().size() == 0) {
                mPackageUserToDotInfos.remove(removedPackageUserKey);
            }
            updateNotificationDots(removedPackageUserKey::equals);
            trimNotifications(mPackageUserToDotInfos);
        }
    }

    @Override
    public void onNotificationFullRefresh(List<StatusBarNotification> activeNotifications) {
        if (activeNotifications == null) return;
        // This will contain the PackageUserKeys which have updated dots.
        HashMap<PackageUserKey, DotInfo> updatedDots = new HashMap<>(mPackageUserToDotInfos);
        mPackageUserToDotInfos.clear();
        for (StatusBarNotification notification : activeNotifications) {
            PackageUserKey packageUserKey = PackageUserKey.fromNotification(notification);
            DotInfo dotInfo = mPackageUserToDotInfos.get(packageUserKey);
            if (dotInfo == null) {
                dotInfo = new DotInfo();
                mPackageUserToDotInfos.put(packageUserKey, dotInfo);
            }
            dotInfo.addOrUpdateNotificationKey(NotificationKeyData.fromNotification(notification));
        }

        // Add and remove from updatedDots so it contains the PackageUserKeys of updated dots.
        for (PackageUserKey packageUserKey : mPackageUserToDotInfos.keySet()) {
            DotInfo prevDot = updatedDots.get(packageUserKey);
            DotInfo newDot = mPackageUserToDotInfos.get(packageUserKey);
            if (prevDot == null
                    || prevDot.getNotificationCount() != newDot.getNotificationCount()) {
                updatedDots.put(packageUserKey, newDot);
            } else {
                // No need to update the dot if it already existed (no visual change).
                // Note that if the dot was removed entirely, we wouldn't reach this point because
                // this loop only includes active notifications added above.
                updatedDots.remove(packageUserKey);
            }
        }

        if (!updatedDots.isEmpty()) {
            updateNotificationDots(updatedDots::containsKey);
        }
        trimNotifications(updatedDots);
    }

    private void trimNotifications(Map<PackageUserKey, DotInfo> updatedDots) {
        mChangeListener.trimNotifications(updatedDots);
    }

    public void setDeepShortcutMap(HashMap<ComponentKey, Integer> deepShortcutMapCopy) {
        mDeepShortcutMap = deepShortcutMapCopy;
        if (LOGD) Log.d(TAG, "bindDeepShortcutMap: " + mDeepShortcutMap);
    }

    public int getShortcutCountForItem(ItemInfo info) {
        if (!ShortcutUtil.supportsDeepShortcuts(info)) {
            return 0;
        }
        ComponentName component = info.getTargetComponent();
        if (component == null) {
            return 0;
        }

        Integer count = mDeepShortcutMap.get(new ComponentKey(component, info.user));
        return count == null ? 0 : count;
        // TODO: Figure out how to get context here to invoke ShortcutRequest constructor.
//        // _TODO: is there really NO more efficient way to do this
//        return new ShortcutRequest(info., info.user)
//                .withContainer(info.getTargetComponent())
//                .query(ShortcutRequest.PUBLISHED);
    }

    public @Nullable DotInfo getDotInfoForItem(@NonNull ItemInfo info) {
        if (!ShortcutUtil.supportsShortcuts(info)) {
            return null;
        }
        DotInfo dotInfo = mPackageUserToDotInfos.get(PackageUserKey.fromItemInfo(info));
        if (dotInfo == null) {
            return null;
        }
        List<NotificationKeyData> notifications = getNotificationsForItem(
                info, dotInfo.getNotificationKeys());
        if (notifications.isEmpty()) {
            return null;
        }
        return dotInfo;
    }

    public @NonNull List<NotificationKeyData> getNotificationKeysForItem(ItemInfo info) {
        DotInfo dotInfo = getDotInfoForItem(info);
        return dotInfo == null ? Collections.EMPTY_LIST
                : getNotificationsForItem(info, dotInfo.getNotificationKeys());
    }

    public void cancelNotification(String notificationKey) {
        NotificationListener notificationListener = NotificationListener.getInstanceIfConnected();
        if (notificationListener == null) {
            return;
        }
        notificationListener.cancelNotificationFromLauncher(notificationKey);
    }

    public void setAllWidgets(ArrayList<WidgetListRowEntry> allWidgets) {
        mAllWidgets = allWidgets;
        mChangeListener.onWidgetsBound();
    }

    public void setChangeListener(PopupDataChangeListener listener) {
        mChangeListener = listener == null ? PopupDataChangeListener.INSTANCE : listener;
    }

    public ArrayList<WidgetListRowEntry> getAllWidgets() {
        return mAllWidgets;
    }

    public List<WidgetItem> getWidgetsForPackageUser(PackageUserKey packageUserKey) {
        for (WidgetListRowEntry entry : mAllWidgets) {
            if (entry.pkgItem.packageName.equals(packageUserKey.mPackageName)) {
                ArrayList<WidgetItem> widgets = new ArrayList<>(entry.widgets);
                // Remove widgets not associated with the correct user.
                Iterator<WidgetItem> iterator = widgets.iterator();
                while (iterator.hasNext()) {
                    if (!iterator.next().user.equals(packageUserKey.mUser)) {
                        iterator.remove();
                    }
                }
                return widgets.isEmpty() ? null : widgets;
            }
        }
        return null;
    }

    /**
     * Returns a list of notifications that are relevant to given ItemInfo.
     */
    public static @NonNull List<NotificationKeyData> getNotificationsForItem(
            @NonNull ItemInfo info, @NonNull List<NotificationKeyData> notifications) {
        String shortcutId = ShortcutUtil.getShortcutIdIfPinnedShortcut(info);
        if (shortcutId == null) {
            return notifications;
        }
        String[] personKeys = ShortcutUtil.getPersonKeysIfPinnedShortcut(info);
        return notifications.stream().filter((NotificationKeyData notification) -> {
                    if (notification.shortcutId != null) {
                        return notification.shortcutId.equals(shortcutId);
                    }
                    if (notification.personKeysFromNotification.length != 0) {
                        return Arrays.equals(notification.personKeysFromNotification, personKeys);
                    }
                    return false;
                }).collect(Collectors.toList());
    }

    public void dump(String prefix, PrintWriter writer) {
        writer.println(prefix + "PopupDataProvider:");
        writer.println(prefix + "\tmPackageUserToDotInfos:" + mPackageUserToDotInfos);
    }

    public interface PopupDataChangeListener {

        PopupDataChangeListener INSTANCE = new PopupDataChangeListener() { };

        default void onNotificationDotsUpdated(Predicate<PackageUserKey> updatedDots) { }

        default void trimNotifications(Map<PackageUserKey, DotInfo> updatedDots) { }

        default void onWidgetsBound() { }
    }
}
