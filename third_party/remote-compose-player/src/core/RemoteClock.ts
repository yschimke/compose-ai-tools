// RemoteClock: abstraction for time source.

export interface TimeSnapshot {
    getMillis(): number;
    getYear(): number;
    getMonth(): number;
    getDayOfMonth(): number;
    getDayOfYear(): number;
    getHour(): number;
    getMinute(): number;
    getSecond(): number;
    getMillisOfSecond(): number;
    getDayOfWeek(): number;
    getOffsetSeconds(): number;
    getContinuousSeconds(): number;
    getEpochSeconds(): number;
    getTimeInSec(): number;
    getTimeInMin(): number;
}

export interface RemoteClock {
    millis(): number;
    snapshot(): TimeSnapshot;
}

function createSnapshot(millis: number): TimeSnapshot {
    const d = new Date(millis);
    const year = d.getFullYear();
    const month = d.getMonth() + 1; // 1-12
    const dayOfMonth = d.getDate();
    const hour = d.getHours();
    const minute = d.getMinutes();
    const second = d.getSeconds();
    const millisOfSecond = d.getMilliseconds();

    // Day of week: Java DayOfWeek convention: 1=Monday..7=Sunday
    const jsDay = d.getDay(); // 0=Sun,1=Mon,...,6=Sat
    const dayOfWeek = jsDay === 0 ? 7 : jsDay; // 1=Mon,...,7=Sun

    // Day of year
    const startOfYear = new Date(year, 0, 1);
    const dayOfYear = Math.floor((millis - startOfYear.getTime()) / 86400000) + 1;

    // UTC offset in seconds (getTimezoneOffset returns minutes, negative means ahead of UTC)
    const offsetSeconds = -d.getTimezoneOffset() * 60;

    return {
        getMillis: () => millis,
        getYear: () => year,
        getMonth: () => month,
        getDayOfMonth: () => dayOfMonth,
        getDayOfYear: () => dayOfYear,
        getHour: () => hour,
        getMinute: () => minute,
        getSecond: () => second,
        getMillisOfSecond: () => millisOfSecond,
        getDayOfWeek: () => dayOfWeek,
        getOffsetSeconds: () => offsetSeconds,
        getContinuousSeconds: () => minute * 60 + second + millisOfSecond * 1E-3,
        getEpochSeconds: () => Math.floor(millis / 1000),
        getTimeInSec: () => minute * 60 + second,
        getTimeInMin: () => hour * 60 + minute,
    };
}

export const SystemClock: RemoteClock = {
    millis(): number {
        return Date.now();
    },
    snapshot(): TimeSnapshot {
        return createSnapshot(Date.now());
    }
};

export const SYSTEM_CLOCK = SystemClock;
