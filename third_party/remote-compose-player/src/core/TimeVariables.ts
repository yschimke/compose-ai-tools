// TimeVariables: populates system time variables into RemoteContext each frame.
// Matches the Java TimeVariables implementation (the authoritative reference).

import type { RemoteClock } from './RemoteClock';
import { RemoteContext } from './RemoteContext';
import { CoreDocument } from './CoreDocument';

export class TimeVariables {
    private mClock: RemoteClock;
    private mLastAnimationTime = -1;
    private mStartTime: number;

    constructor(clock: RemoteClock) {
        this.mClock = clock;
        this.mStartTime = performance.now() / 1000;
    }

    getClock(): RemoteClock { return this.mClock; }

    /** Seconds elapsed since this TimeVariables was created. */
    getElapsedSeconds(): number {
        return performance.now() / 1000 - this.mStartTime;
    }

    updateTime(context: RemoteContext): void {
        const snapshot = this.mClock.snapshot();

        // All time variables use loadFloat to match Java TimeVariables.
        context.loadFloat(RemoteContext.ID_OFFSET_TO_UTC, snapshot.getOffsetSeconds());
        context.loadFloat(RemoteContext.ID_CONTINUOUS_SEC, snapshot.getContinuousSeconds());
        context.loadInteger(RemoteContext.ID_EPOCH_SECOND, snapshot.getEpochSeconds());
        context.loadFloat(RemoteContext.ID_TIME_IN_SEC, snapshot.getTimeInSec());
        context.loadFloat(RemoteContext.ID_TIME_IN_MIN, snapshot.getTimeInMin());
        context.loadFloat(RemoteContext.ID_TIME_IN_HR, snapshot.getHour());
        context.loadFloat(RemoteContext.ID_CALENDAR_MONTH, snapshot.getMonth());
        context.loadFloat(RemoteContext.ID_DAY_OF_MONTH, snapshot.getDayOfMonth());
        context.loadFloat(RemoteContext.ID_WEEK_DAY, snapshot.getDayOfWeek());
        context.loadFloat(RemoteContext.ID_DAY_OF_YEAR, snapshot.getDayOfYear());
        context.loadFloat(RemoteContext.ID_YEAR, snapshot.getYear());

        // Animation time = seconds since document load
        const animTime = this.getElapsedSeconds();
        const deltaTime = this.mLastAnimationTime >= 0 ? animTime - this.mLastAnimationTime : 0;
        this.mLastAnimationTime = animTime;
        context.loadFloat(RemoteContext.ID_ANIMATION_TIME, animTime);
        context.setAnimationTime(animTime);
        context.loadFloat(RemoteContext.ID_ANIMATION_DELTA_TIME, deltaTime);

        context.loadFloat(RemoteContext.ID_API_LEVEL, CoreDocument.DOCUMENT_API_LEVEL);
    }
}
