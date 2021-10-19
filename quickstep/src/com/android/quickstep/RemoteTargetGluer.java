/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.quickstep;

import android.content.Context;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.launcher3.util.SplitConfigurationOptions;
import com.android.quickstep.util.AnimatorControllerWithResistance;
import com.android.quickstep.util.LauncherSplitScreenListener;
import com.android.quickstep.util.TaskViewSimulator;
import com.android.quickstep.util.TransformParams;
import com.android.systemui.shared.system.RemoteAnimationTargetCompat;

/**
 * Glues together the necessary components to animate a remote target using a
 * {@link TaskViewSimulator}
 */
public class RemoteTargetGluer {
    private static final String TAG = "RemoteTargetGluer";

    private RemoteTargetHandle[] mRemoteTargetHandles;
    private SplitConfigurationOptions.StagedSplitBounds mStagedSplitBounds;

    /**
     * Use this constructor if remote targets are split-screen independent
     */
    public RemoteTargetGluer(Context context, BaseActivityInterface sizingStrategy,
            RemoteAnimationTargets targets) {
        mRemoteTargetHandles = createHandles(context, sizingStrategy, targets.apps.length);
    }

    /**
     * Use this constructor if you want the number of handles created to match the number of active
     * running tasks
     */
    public RemoteTargetGluer(Context context, BaseActivityInterface sizingStrategy) {
        int[] splitIds = LauncherSplitScreenListener.INSTANCE.getNoCreate()
                .getRunningSplitTaskIds();
        mRemoteTargetHandles = createHandles(context, sizingStrategy, splitIds.length == 2 ? 2 : 1);
    }

    private RemoteTargetHandle[] createHandles(Context context,
            BaseActivityInterface sizingStrategy, int numHandles) {
        RemoteTargetHandle[] handles = new RemoteTargetHandle[numHandles];
        for (int i = 0; i < numHandles; i++) {
            TaskViewSimulator tvs = new TaskViewSimulator(context, sizingStrategy);
            TransformParams transformParams = new TransformParams();
            handles[i] = new RemoteTargetHandle(tvs, transformParams);
        }
        return handles;
    }

    /**
     * Pairs together {@link TaskViewSimulator}s and {@link TransformParams} into a
     * {@link RemoteTargetHandle}
     * Assigns only the apps associated with {@param targets} into their own TaskViewSimulators.
     * Length of targets.apps should match that of {@link #mRemoteTargetHandles}.
     *
     * If split screen may be active when this is called, you might want to use
     * {@link #assignTargetsForSplitScreen(RemoteAnimationTargets)}
     */
    public RemoteTargetHandle[] assignTargets(RemoteAnimationTargets targets) {
        for (int i = 0; i < mRemoteTargetHandles.length; i++) {
            RemoteAnimationTargetCompat primaryTaskTarget = targets.apps[i];
            mRemoteTargetHandles[i].mTransformParams.setTargetSet(
                    createRemoteAnimationTargetsForTarget(primaryTaskTarget, targets));
            mRemoteTargetHandles[i].mTaskViewSimulator.setPreview(primaryTaskTarget, null);
        }
        return mRemoteTargetHandles;
    }

    /**
     * Similar to {@link #assignTargets(RemoteAnimationTargets)}, except this matches the
     * apps in targets.apps to that of the split screened tasks. If split screen is active, then
     * {@link #mRemoteTargetHandles} index 0 will be the left/top task, index one right/bottom
     */
    public RemoteTargetHandle[] assignTargetsForSplitScreen(RemoteAnimationTargets targets) {
        int[] splitIds = LauncherSplitScreenListener.INSTANCE.getNoCreate()
                .getRunningSplitTaskIds();
        if (splitIds.length == 0 && mRemoteTargetHandles.length > 1) {
            // There's a chance that between the creation of this class and assigning targets,
            // LauncherSplitScreenListener may have received callback that removes split
            mRemoteTargetHandles = new RemoteTargetHandle[]{mRemoteTargetHandles[0]};
            Log.w(TAG, "splitTaskIds changed between creation and assignment");
        }

        RemoteAnimationTargetCompat primaryTaskTarget;
        RemoteAnimationTargetCompat secondaryTaskTarget;
        if (mRemoteTargetHandles.length == 1) {
            // If we're not in split screen, the splitIds count doesn't really matter since we
            // should always hit this case. Right now there's no use case for multiple app targets
            // without being in split screen
            primaryTaskTarget = targets.apps[0];
            mRemoteTargetHandles[0].mTransformParams.setTargetSet(targets);
            mRemoteTargetHandles[0].mTaskViewSimulator.setPreview(primaryTaskTarget, null);
        } else {
            // split screen
            primaryTaskTarget = targets.findTask(splitIds[0]);
            secondaryTaskTarget = targets.findTask(splitIds[1]);

            mStagedSplitBounds = new SplitConfigurationOptions.StagedSplitBounds(
                    primaryTaskTarget.screenSpaceBounds,
                    secondaryTaskTarget.screenSpaceBounds);
            mRemoteTargetHandles[0].mTransformParams.setTargetSet(
                    createRemoteAnimationTargetsForTarget(primaryTaskTarget, targets));
            mRemoteTargetHandles[0].mTaskViewSimulator.setPreview(primaryTaskTarget,
                    mStagedSplitBounds);

            mRemoteTargetHandles[1].mTransformParams.setTargetSet(
                    createRemoteAnimationTargetsForTarget(secondaryTaskTarget, targets));
            mRemoteTargetHandles[1].mTaskViewSimulator.setPreview(secondaryTaskTarget,
                    mStagedSplitBounds);
        }
        return mRemoteTargetHandles;
    }

    private RemoteAnimationTargets createRemoteAnimationTargetsForTarget(
            RemoteAnimationTargetCompat target,
            RemoteAnimationTargets targets) {
        return new RemoteAnimationTargets(new RemoteAnimationTargetCompat[]{target},
                targets.wallpapers, targets.nonApps, targets.targetMode);
    }

    public RemoteTargetHandle[] getRemoteTargetHandles() {
        return mRemoteTargetHandles;
    }

    public SplitConfigurationOptions.StagedSplitBounds getStagedSplitBounds() {
        return mStagedSplitBounds;
    }

    /**
     * Container to keep together all the associated objects whose properties need to be updated to
     * animate a single remote app target
     */
    public static class RemoteTargetHandle {
        private final TaskViewSimulator mTaskViewSimulator;
        private final TransformParams mTransformParams;
        @Nullable
        private AnimatorControllerWithResistance mPlaybackController;

        public RemoteTargetHandle(TaskViewSimulator taskViewSimulator,
                TransformParams transformParams) {
            mTransformParams = transformParams;
            mTaskViewSimulator = taskViewSimulator;
        }

        public TaskViewSimulator getTaskViewSimulator() {
            return mTaskViewSimulator;
        }

        public TransformParams getTransformParams() {
            return mTransformParams;
        }

        @Nullable
        public AnimatorControllerWithResistance getPlaybackController() {
            return mPlaybackController;
        }

        public void setPlaybackController(
                @Nullable AnimatorControllerWithResistance playbackController) {
            mPlaybackController = playbackController;
        }
    }
}