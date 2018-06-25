/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.calcite.avatica.util;

import java.text.DateFormat;
import java.text.NumberFormat;
import java.text.ParsePosition;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Utility functions for datetime types: date, time, timestamp.
 *
 * <p>Used by the JDBC driver.
 *
 * <p>TODO: review methods for performance. Due to allocations required, it may
 * be preferable to introduce a "formatter" with the required state.
 */
public class DateTimeUtils {
  /** The julian date of the epoch, 1970-01-01. */
  public static final int EPOCH_JULIAN = 2440588;

  private DateTimeUtils() {}

  //~ Static fields/initializers ---------------------------------------------

  /** The SimpleDateFormat string for ISO dates, "yyyy-MM-dd". */
  public static final String DATE_FORMAT_STRING = "yyyy-MM-dd";

  /** The SimpleDateFormat string for ISO times, "HH:mm:ss". */
  public static final String TIME_FORMAT_STRING = "HH:mm:ss";

  /** The SimpleDateFormat string for ISO timestamps, "yyyy-MM-dd HH:mm:ss". */
  public static final String TIMESTAMP_FORMAT_STRING =
      DATE_FORMAT_STRING + " " + TIME_FORMAT_STRING;

  /** The GMT time zone.
   *
   * @deprecated Use {@link #UTC_ZONE} */
  @Deprecated // to be removed before 2.0
  public static final TimeZone GMT_ZONE = TimeZone.getTimeZone("GMT");

  /** The UTC time zone. */
  public static final TimeZone UTC_ZONE = TimeZone.getTimeZone("UTC");

  /** The Java default time zone. */
  public static final TimeZone DEFAULT_ZONE = TimeZone.getDefault();

  /**
   * The number of milliseconds in a second.
   */
  public static final long MILLIS_PER_SECOND = 1000L;

  /**
   * The number of milliseconds in a minute.
   */
  public static final long MILLIS_PER_MINUTE = 60000L;

  /**
   * The number of milliseconds in an hour.
   */
  public static final long MILLIS_PER_HOUR = 3600000L; // = 60 * 60 * 1000

  /**
   * The number of milliseconds in a day.
   *
   * <p>This is the modulo 'mask' used when converting
   * TIMESTAMP values to DATE and TIME values.
   */
  public static final long MILLIS_PER_DAY = 86400000; // = 24 * 60 * 60 * 1000

  /**
   * The number of seconds in a day.
   */
  public static final long SECONDS_PER_DAY = 86_400; // = 24 * 60 * 60

  /**
   * Calendar set to the epoch (1970-01-01 00:00:00 UTC). Useful for
   * initializing other values. Calendars are not immutable, so be careful not
   * to screw up this object for everyone else.
   */
  public static final Calendar ZERO_CALENDAR;

  private static final OffsetDateTimeHandler OFFSET_DATE_TIME_HANDLER;

  static {
    ZERO_CALENDAR = Calendar.getInstance(DateTimeUtils.UTC_ZONE, Locale.ROOT);
    ZERO_CALENDAR.setTimeInMillis(0);
    OffsetDateTimeHandler h;
    try {
      h = new ReflectiveOffsetDateTimeHandler();
    } catch (ClassNotFoundException e) {
      h = new NoopOffsetDateTimeHandler();
    }
    OFFSET_DATE_TIME_HANDLER = h;
  }

  //~ Methods ----------------------------------------------------------------

  /**
   * Parses a string using {@link SimpleDateFormat} and a given pattern. This
   * method parses a string at the specified parse position and if successful,
   * updates the parse position to the index after the last character used.
   * The parsing is strict and requires months to be less than 12, days to be
   * less than 31, etc.
   *
   * @param s       string to be parsed
   * @param dateFormat Date format
   * @param tz      time zone in which to interpret string. Defaults to the Java
   *                default time zone
   * @param pp      position to start parsing from
   * @return a Calendar initialized with the parsed value, or null if parsing
   * failed. If returned, the Calendar is configured to the GMT time zone.
   */
  private static Calendar parseDateFormat(String s, DateFormat dateFormat,
      TimeZone tz, ParsePosition pp) {
    if (tz == null) {
      tz = DEFAULT_ZONE;
    }
    Calendar ret = Calendar.getInstance(tz, Locale.ROOT);
    dateFormat.setCalendar(ret);
    dateFormat.setLenient(false);

    final Date d = dateFormat.parse(s, pp);
    if (null == d) {
      return null;
    }
    ret.setTime(d);
    ret.setTimeZone(UTC_ZONE);
    return ret;
  }

  @Deprecated // to be removed before 2.0
  public static Calendar parseDateFormat(String s, String pattern,
      TimeZone tz) {
    return parseDateFormat(s, new SimpleDateFormat(pattern, Locale.ROOT), tz);
  }

  /**
   * Parses a string using {@link SimpleDateFormat} and a given pattern. The
   * entire string must match the pattern specified.
   *
   * @param s       string to be parsed
   * @param dateFormat Date format
   * @param tz      time zone in which to interpret string. Defaults to the Java
   *                default time zone
   * @return a Calendar initialized with the parsed value, or null if parsing
   * failed. If returned, the Calendar is configured to the UTC time zone.
   */
  public static Calendar parseDateFormat(String s, DateFormat dateFormat,
      TimeZone tz) {
    ParsePosition pp = new ParsePosition(0);
    Calendar ret = parseDateFormat(s, dateFormat, tz, pp);
    if (pp.getIndex() != s.length()) {
      // Didn't consume entire string - not good
      return null;
    }
    return ret;
  }

  @Deprecated // to be removed before 2.0
  public static PrecisionTime parsePrecisionDateTimeLiteral(
      String s,
      String pattern,
      TimeZone tz) {
    assert pattern != null;
    return parsePrecisionDateTimeLiteral(s,
        new SimpleDateFormat(pattern, Locale.ROOT), tz, 3);
  }

  /**
   * Parses a string using {@link SimpleDateFormat} and a given pattern, and
   * if present, parses a fractional seconds component. The fractional seconds
   * component must begin with a decimal point ('.') followed by numeric
   * digits. The precision is rounded to a maximum of 3 digits of fractional
   * seconds precision (to obtain milliseconds).
   *
   * @param s       string to be parsed
   * @param dateFormat Date format
   * @param tz      time zone in which to interpret string. Defaults to the
   *                local time zone
   * @return a {@link DateTimeUtils.PrecisionTime PrecisionTime} initialized
   * with the parsed value, or null if parsing failed. The PrecisionTime
   * contains a GMT Calendar and a precision.
   */
  public static PrecisionTime parsePrecisionDateTimeLiteral(String s,
      DateFormat dateFormat, TimeZone tz, int maxPrecision) {
    final ParsePosition pp = new ParsePosition(0);
    final Calendar cal = parseDateFormat(s, dateFormat, tz, pp);
    if (cal == null) {
      return null; // Invalid date/time format
    }

    // Note: the Java SimpleDateFormat 'S' treats any number after
    // the decimal as milliseconds. That means 12:00:00.9 has 9
    // milliseconds and 12:00:00.9999 has 9999 milliseconds.
    int p = 0;
    String secFraction = "";
    if (pp.getIndex() < s.length()) {
      // Check to see if rest is decimal portion
      if (s.charAt(pp.getIndex()) != '.') {
        return null;
      }

      // Skip decimal sign
      pp.setIndex(pp.getIndex() + 1);

      // Parse decimal portion
      if (pp.getIndex() < s.length()) {
        secFraction = s.substring(pp.getIndex());
        if (!secFraction.matches("\\d+")) {
          return null;
        }
        NumberFormat nf = NumberFormat.getIntegerInstance(Locale.ROOT);
        Number num = nf.parse(s, pp);
        if ((num == null) || (pp.getIndex() != s.length())) {
          // Invalid decimal portion
          return null;
        }

        // Determine precision - only support prec 3 or lower
        // (milliseconds) Higher precisions are quietly rounded away
        p = secFraction.length();
        if (maxPrecision >= 0) {
          // If there is a maximum precision, ignore subsequent digits
          p = Math.min(maxPrecision, p);
          secFraction = secFraction.substring(0, p);
        }

        // Calculate milliseconds
        String millis = secFraction;
        if (millis.length() > 3) {
          millis = secFraction.substring(0, 3);
        }
        while (millis.length() < 3) {
          millis = millis + "0";
        }

        int ms = Integer.parseInt(millis);
        cal.add(Calendar.MILLISECOND, ms);
      }
    }

    assert pp.getIndex() == s.length();
    return new PrecisionTime(cal, secFraction, p);
  }

  /**
   * Gets the active time zone based on a Calendar argument
   */
  public static TimeZone getTimeZone(Calendar cal) {
    if (cal == null) {
      return DEFAULT_ZONE;
    }
    return cal.getTimeZone();
  }

  /**
   * Checks if the date/time format is valid
   *
   * @param pattern {@link SimpleDateFormat}  pattern
   * @throws IllegalArgumentException if the given pattern is invalid
   */
  public static void checkDateFormat(String pattern) {
    new SimpleDateFormat(pattern, Locale.ROOT);
  }

  /**
   * Creates a new date formatter with Farrago specific options. Farrago
   * parsing is strict and does not allow values such as day 0, month 13, etc.
   *
   * @param format {@link SimpleDateFormat}  pattern
   */
  public static SimpleDateFormat newDateFormat(String format) {
    SimpleDateFormat sdf = new SimpleDateFormat(format, Locale.ROOT);
    sdf.setLenient(false);
    return sdf;
  }

  /** Helper for CAST({timestamp} AS VARCHAR(n)). */
  public static String unixTimestampToString(long timestamp) {
    return unixTimestampToString(timestamp, 0);
  }

  public static String unixTimestampToString(long timestamp, int precision) {
    final StringBuilder buf = new StringBuilder(17);
    int date = (int) (timestamp / MILLIS_PER_DAY);
    int time = (int) (timestamp % MILLIS_PER_DAY);
    if (time < 0) {
      --date;
      time += MILLIS_PER_DAY;
    }
    unixDateToString(buf, date);
    buf.append(' ');
    unixTimeToString(buf, time, precision);
    return buf.toString();
  }

  /** Helper for CAST({timestamp} AS VARCHAR(n)). */
  public static String unixTimeToString(int time) {
    return unixTimeToString(time, 0);
  }

  public static String unixTimeToString(int time, int precision) {
    final StringBuilder buf = new StringBuilder(8);
    unixTimeToString(buf, time, precision);
    return buf.toString();
  }

  private static void unixTimeToString(StringBuilder buf, int time,
      int precision) {
    int h = time / 3600000;
    int time2 = time % 3600000;
    int m = time2 / 60000;
    int time3 = time2 % 60000;
    int s = time3 / 1000;
    int ms = time3 % 1000;
    int2(buf, h);
    buf.append(':');
    int2(buf, m);
    buf.append(':');
    int2(buf, s);
    if (precision > 0) {
      buf.append('.');
      while (precision > 0) {
        buf.append((char) ('0' + (ms / 100)));
        ms = ms % 100;
        ms = ms * 10;
        --precision;
      }
    }
  }

  private static void int2(StringBuilder buf, int i) {
    buf.append((char) ('0' + (i / 10) % 10));
    buf.append((char) ('0' + i % 10));
  }

  private static void int3(StringBuilder buf, int i) {
    buf.append((char) ('0' + (i / 100) % 10));
    buf.append((char) ('0' + (i / 10) % 10));
    buf.append((char) ('0' + i % 10));
  }

  private static void int4(StringBuilder buf, int i) {
    buf.append((char) ('0' + (i / 1000) % 10));
    buf.append((char) ('0' + (i / 100) % 10));
    buf.append((char) ('0' + (i / 10) % 10));
    buf.append((char) ('0' + i % 10));
  }

  /** Helper for CAST({date} AS VARCHAR(n)). */
  public static String unixDateToString(int date) {
    final StringBuilder buf = new StringBuilder(10);
    unixDateToString(buf, date);
    return buf.toString();
  }

  private static void unixDateToString(StringBuilder buf, int date) {
    julianToString(buf, date + EPOCH_JULIAN);
  }

  private static void julianToString(StringBuilder buf, int julian) {
    // this shifts the epoch back to astronomical year -4800 instead of the
    // start of the Christian era in year AD 1 of the proleptic Gregorian
    // calendar.
    int j = julian + 32044;
    int g = j / 146097;
    int dg = j % 146097;
    int c = (dg / 36524 + 1) * 3 / 4;
    int dc = dg - c * 36524;
    int b = dc / 1461;
    int db = dc % 1461;
    int a = (db / 365 + 1) * 3 / 4;
    int da = db - a * 365;

    // integer number of full years elapsed since March 1, 4801 BC
    int y = g * 400 + c * 100 + b * 4 + a;
    // integer number of full months elapsed since the last March 1
    int m = (da * 5 + 308) / 153 - 2;
    // number of days elapsed since day 1 of the month
    int d = da - (m + 4) * 153 / 5 + 122;
    int year = y - 4800 + (m + 2) / 12;
    int month = (m + 2) % 12 + 1;
    int day = d + 1;
    int4(buf, year);
    buf.append('-');
    int2(buf, month);
    buf.append('-');
    int2(buf, day);
  }

  public static String intervalYearMonthToString(int v, TimeUnitRange range) {
    final StringBuilder buf = new StringBuilder();
    if (v >= 0) {
      buf.append('+');
    } else {
      buf.append('-');
      v = -v;
    }
    final int y;
    final int m;
    switch (range) {
    case YEAR:
      v = roundUp(v, 12);
      y = v / 12;
      buf.append(y);
      break;
    case YEAR_TO_MONTH:
      y = v / 12;
      buf.append(y);
      buf.append('-');
      m = v % 12;
      number(buf, m, 2);
      break;
    case MONTH:
      m = v;
      buf.append(m);
      break;
    default:
      throw new AssertionError(range);
    }
    return buf.toString();
  }

  public static StringBuilder number(StringBuilder buf, int v, int n) {
    for (int k = digitCount(v); k < n; k++) {
      buf.append('0');
    }
    return buf.append(v);
  }

  public static int digitCount(int v) {
    for (int n = 1;; n++) {
      v /= 10;
      if (v == 0) {
        return n;
      }
    }
  }

  private static int roundUp(int dividend, int divisor) {
    int remainder = dividend % divisor;
    dividend -= remainder;
    if (remainder * 2 > divisor) {
      dividend += divisor;
    }
    return dividend;
  }

  /** Cheap, unsafe, long power. power(2, 3) returns 8. */
  public static long powerX(long a, long b) {
    long x = 1;
    while (b > 0) {
      x *= a;
      --b;
    }
    return x;
  }

  public static String intervalDayTimeToString(long v, TimeUnitRange range,
      int scale) {
    final StringBuilder buf = new StringBuilder();
    if (v >= 0) {
      buf.append('+');
    } else {
      buf.append('-');
      v = -v;
    }
    final long ms;
    final long s;
    final long m;
    final long h;
    final long d;
    switch (range) {
    case DAY_TO_SECOND:
      v = roundUp(v, powerX(10, 3 - scale));
      ms = v % 1000;
      v /= 1000;
      s = v % 60;
      v /= 60;
      m = v % 60;
      v /= 60;
      h = v % 24;
      v /= 24;
      d = v;
      buf.append((int) d);
      buf.append(' ');
      number(buf, (int) h, 2);
      buf.append(':');
      number(buf, (int) m, 2);
      buf.append(':');
      number(buf, (int) s, 2);
      fraction(buf, scale, ms);
      break;
    case DAY_TO_MINUTE:
      v = roundUp(v, 1000 * 60);
      v /= 1000;
      v /= 60;
      m = v % 60;
      v /= 60;
      h = v % 24;
      v /= 24;
      d = v;
      ms = Long.MIN_VALUE;
      s = Long.MIN_VALUE;
      buf.append((int) d);
      buf.append(' ');
      number(buf, (int) h, 2);
      buf.append(':');
      number(buf, (int) m, 2);
      break;
    case DAY_TO_HOUR:
      v = roundUp(v, 1000 * 60 * 60);
      v /= 1000;
      v /= 60;
      v /= 60;
      h = v % 24;
      v /= 24;
      d = v;
      ms = Long.MIN_VALUE;
      s = Long.MIN_VALUE;
      m = Long.MIN_VALUE;
      buf.append((int) d);
      buf.append(' ');
      number(buf, (int) h, 2);
      break;
    case DAY:
      v = roundUp(v, 1000 * 60 * 60 * 24);
      d = v / (1000 * 60 * 60 * 24);
      ms = Long.MIN_VALUE;
      s = Long.MIN_VALUE;
      m = Long.MIN_VALUE;
      h = Long.MIN_VALUE;
      buf.append((int) d);
      break;
    case HOUR:
      v = roundUp(v, 1000 * 60 * 60);
      v /= 1000;
      v /= 60;
      v /= 60;
      h = v;
      ms = Long.MIN_VALUE;
      s = Long.MIN_VALUE;
      m = Long.MIN_VALUE;
      d = Long.MIN_VALUE;
      buf.append((int) h);
      break;
    case HOUR_TO_MINUTE:
      v = roundUp(v, 1000 * 60);
      v /= 1000;
      v /= 60;
      m = v % 60;
      v /= 60;
      h = v;
      ms = Long.MIN_VALUE;
      s = Long.MIN_VALUE;
      d = Long.MIN_VALUE;
      buf.append((int) h);
      buf.append(':');
      number(buf, (int) m, 2);
      break;
    case HOUR_TO_SECOND:
      v = roundUp(v, powerX(10, 3 - scale));
      ms = v % 1000;
      v /= 1000;
      s = v % 60;
      v /= 60;
      m = v % 60;
      v /= 60;
      h = v;
      d = Long.MIN_VALUE;
      buf.append((int) h);
      buf.append(':');
      number(buf, (int) m, 2);
      buf.append(':');
      number(buf, (int) s, 2);
      fraction(buf, scale, ms);
      break;
    case MINUTE_TO_SECOND:
      v = roundUp(v, powerX(10, 3 - scale));
      ms = v % 1000;
      v /= 1000;
      s = v % 60;
      v /= 60;
      m = v;
      d = Long.MIN_VALUE;
      h = Long.MIN_VALUE;
      buf.append((int) m);
      buf.append(':');
      number(buf, (int) s, 2);
      fraction(buf, scale, ms);
      break;
    case MINUTE:
      v = roundUp(v, 1000 * 60);
      v /= 1000;
      v /= 60;
      m = v;
      ms = Long.MIN_VALUE;
      s = Long.MIN_VALUE;
      d = Long.MIN_VALUE;
      h = Long.MIN_VALUE;
      buf.append((int) m);
      break;
    case SECOND:
      v = roundUp(v, powerX(10, 3 - scale));
      ms = v % 1000;
      v /= 1000;
      s = v;
      d = Long.MIN_VALUE;
      m = Long.MIN_VALUE;
      h = Long.MIN_VALUE;
      buf.append((int) s);
      fraction(buf, scale, ms);
      break;
    default:
      throw new AssertionError(range);
    }
    return buf.toString();
  }

  /**
   * Rounds a dividend to the nearest divisor.
   * For example roundUp(31, 10) yields 30; roundUp(37, 10) yields 40.
   * @param dividend Number to be divided
   * @param divisor Number to divide by
   * @return Rounded dividend
   */
  private static long roundUp(long dividend, long divisor) {
    long remainder = dividend % divisor;
    dividend -= remainder;
    if (remainder * 2 > divisor) {
      dividend += divisor;
    }
    return dividend;
  }

  private static void fraction(StringBuilder buf, int scale, long ms) {
    if (scale > 0) {
      buf.append('.');
      long v1 = scale == 3 ? ms
          : scale == 2 ? ms / 10
          : scale == 1 ? ms / 100
            : 0;
      number(buf, (int) v1, scale);
    }
  }

  public static int dateStringToUnixDate(String s) {
    int hyphen1 = s.indexOf('-');
    int y;
    int m;
    int d;
    if (hyphen1 < 0) {
      y = Integer.parseInt(s.trim());
      m = 1;
      d = 1;
    } else {
      y = Integer.parseInt(s.substring(0, hyphen1).trim());
      final int hyphen2 = s.indexOf('-', hyphen1 + 1);
      if (hyphen2 < 0) {
        m = Integer.parseInt(s.substring(hyphen1 + 1).trim());
        d = 1;
      } else {
        m = Integer.parseInt(s.substring(hyphen1 + 1, hyphen2).trim());
        d = Integer.parseInt(s.substring(hyphen2 + 1).trim());
      }
    }
    return ymdToUnixDate(y, m, d);
  }

  public static int timeStringToUnixDate(String v) {
    return timeStringToUnixDate(v, 0);
  }

  public static int timeStringToUnixDate(String v, int start) {
    final int colon1 = v.indexOf(':', start);
    int hour;
    int minute;
    int second;
    int milli;
    if (colon1 < 0) {
      hour = Integer.parseInt(v.trim());
      minute = 1;
      second = 1;
      milli = 0;
    } else {
      hour = Integer.parseInt(v.substring(start, colon1).trim());
      final int colon2 = v.indexOf(':', colon1 + 1);
      if (colon2 < 0) {
        minute = Integer.parseInt(v.substring(colon1 + 1).trim());
        second = 1;
        milli = 0;
      } else {
        minute = Integer.parseInt(v.substring(colon1 + 1, colon2).trim());
        int dot = v.indexOf('.', colon2);
        if (dot < 0) {
          second = Integer.parseInt(v.substring(colon2 + 1).trim());
          milli = 0;
        } else {
          second = Integer.parseInt(v.substring(colon2 + 1, dot).trim());
          milli = parseFraction(v.substring(dot + 1).trim(), 100);
        }
      }
    }
    return hour * (int) MILLIS_PER_HOUR
        + minute * (int) MILLIS_PER_MINUTE
        + second * (int) MILLIS_PER_SECOND
        + milli;
  }

  /** Parses a fraction, multiplying the first character by {@code multiplier},
   * the second character by {@code multiplier / 10},
   * the third character by {@code multiplier / 100}, and so forth.
   *
   * <p>For example, {@code parseFraction("1234", 100)} yields {@code 123}. */
  private static int parseFraction(String v, int multiplier) {
    int r = 0;
    for (int i = 0; i < v.length(); i++) {
      char c = v.charAt(i);
      int x = c < '0' || c > '9' ? 0 : (c - '0');
      r += multiplier * x;
      if (multiplier < 10) {
        // We're at the last digit. Check for rounding.
        if (i + 1 < v.length()
            && v.charAt(i + 1) >= '5') {
          ++r;
        }
        break;
      }
      multiplier /= 10;
    }
    return r;
  }

  public static long timestampStringToUnixDate(String s) {
    final long d;
    final long t;
    s = s.trim();
    int space = s.indexOf(' ');
    if (space >= 0) {
      d = dateStringToUnixDate(s.substring(0, space));
      t = timeStringToUnixDate(s, space + 1);
    } else {
      d = dateStringToUnixDate(s);
      t = 0;
    }
    return d * MILLIS_PER_DAY + t;
  }

  public static long unixDateExtract(TimeUnitRange range, long date) {
    switch (range) {
    case EPOCH:
      // no need to extract year/month/day, just multiply
      return date * SECONDS_PER_DAY;
    default:
      return julianExtract(range, (int) date + EPOCH_JULIAN);
    }
  }

  private static int julianExtract(TimeUnitRange range, int julian) {
    // this shifts the epoch back to astronomical year -4800 instead of the
    // start of the Christian era in year AD 1 of the proleptic Gregorian
    // calendar.
    int j = julian + 32044;
    int g = j / 146097;
    int dg = j % 146097;
    int c = (dg / 36524 + 1) * 3 / 4;
    int dc = dg - c * 36524;
    int b = dc / 1461;
    int db = dc % 1461;
    int a = (db / 365 + 1) * 3 / 4;
    int da = db - a * 365;

    // integer number of full years elapsed since March 1, 4801 BC
    int y = g * 400 + c * 100 + b * 4 + a;
    // integer number of full months elapsed since the last March 1
    int m = (da * 5 + 308) / 153 - 2;
    // number of days elapsed since day 1 of the month
    int d = da - (m + 4) * 153 / 5 + 122;
    int year = y - 4800 + (m + 2) / 12;
    int month = (m + 2) % 12 + 1;
    int day = d + 1;
    switch (range) {
    case YEAR:
      return year;
    case ISOYEAR:
      int weekNumber = getIso8601WeekNumber(julian, year, month, day);
      if (weekNumber == 1 && month == 12) {
        return year + 1;
      } else if (month == 1 && weekNumber > 50) {
        return year - 1;
      }
      return year;
    case QUARTER:
      return (month + 2) / 3;
    case MONTH:
      return month;
    case DAY:
      return day;
    case DOW:
      return (int) floorMod(julian + 1, 7) + 1; // sun=1, sat=7
    case ISODOW:
      return (int) floorMod(julian, 7) + 1; // mon=1, sun=7
    case WEEK:
      return getIso8601WeekNumber(julian, year, month, day);
    case DOY:
      final long janFirst = ymdToJulian(year, 1, 1);
      return (int) (julian - janFirst) + 1;
    case DECADE:
      return year / 10;
    case CENTURY:
      return year > 0
          ? (year + 99) / 100
          : (year - 99) / 100;
    case MILLENNIUM:
      return year > 0
          ? (year + 999) / 1000
          : (year - 999) / 1000;
    default:
      throw new AssertionError(range);
    }
  }

  /** Returns the first day of the first week of a year.
   * Per ISO-8601 it is the Monday of the week that contains Jan 4,
   * or equivalently, it is a Monday between Dec 29 and Jan 4.
   * Sometimes it is in the year before the given year. */
  private static long firstMondayOfFirstWeek(int year) {
    final long janFirst = ymdToJulian(year, 1, 1);
    final long janFirstDow = floorMod(janFirst + 1, 7); // sun=0, sat=6
    return janFirst + (11 - janFirstDow) % 7 - 3;
  }

  /** Returns the ISO-8601 week number based on year, month, day.
   * Per ISO-8601 it is the Monday of the week that contains Jan 4,
   * or equivalently, it is a Monday between Dec 29 and Jan 4.
   * Sometimes it is in the year before the given year, sometimes after. */
  private static int getIso8601WeekNumber(int julian, int year, int month, int day) {
    long fmofw = firstMondayOfFirstWeek(year);
    if (month == 12 && day > 28) {
      if (31 - day + 4 > 7 - ((int) floorMod(julian, 7) + 1)
          && 31 - day + (int) (floorMod(julian, 7) + 1) >= 4) {
        return (int) (julian - fmofw) / 7 + 1;
      } else {
        return 1;
      }
    } else if (month == 1 && day < 5) {
      if (4 - day <= 7 - ((int) floorMod(julian, 7) + 1)
          && day - ((int) (floorMod(julian, 7) + 1)) >= -3) {
        return 1;
      } else {
        return (int) (julian - firstMondayOfFirstWeek(year - 1)) / 7 + 1;
      }
    }
    return (int) (julian - fmofw) / 7 + 1;
  }

  /** Returns the ISO-8601 year number based on year, month, iso8601 week number. */
  private static int getIso8601Year(int year, int month, int iso8601WeekNumber) {
    int isoYear = year;
    if (iso8601WeekNumber == 1 && month == 12) {
      ++isoYear;
    } else if (month == 1 && iso8601WeekNumber > 50) {
      --isoYear;
    }
    return isoYear;
  }

  /** Returns year's week number (1-53). */
  private static int getWeekOfYear(int julian, int year) {
    return ((julian - ymdToJulian(year, 1, 1)) + 1) / 7;
  }

  /** Extracts a time unit from a UNIX date (milliseconds since epoch). */
  public static int unixTimestampExtract(TimeUnitRange range,
      long timestamp) {
    return unixTimeExtract(range, (int) floorMod(timestamp, MILLIS_PER_DAY));
  }

  /** Extracts a time unit from a time value (milliseconds since midnight). */
  public static int unixTimeExtract(TimeUnitRange range, int time) {
    assert time >= 0;
    assert time < MILLIS_PER_DAY;
    switch (range) {
    case HOUR:
      return time / (int) MILLIS_PER_HOUR;
    case MINUTE:
      final int minutes = time / (int) MILLIS_PER_MINUTE;
      return minutes % 60;
    case SECOND:
      final int seconds = time / (int) MILLIS_PER_SECOND;
      return seconds % 60;
    default:
      throw new AssertionError(range);
    }
  }

  /** Resets to zero the "time" part of a timestamp. */
  public static long resetTime(long timestamp) {
    int date = (int) (timestamp / MILLIS_PER_DAY);
    return (long) date * MILLIS_PER_DAY;
  }

  /** Resets to epoch (1970-01-01) the "date" part of a timestamp. */
  public static long resetDate(long timestamp) {
    return floorMod(timestamp, MILLIS_PER_DAY);
  }

  public static long unixTimestampFloor(TimeUnitRange range, long timestamp) {
    int date = (int) (timestamp / MILLIS_PER_DAY);
    final int f = julianDateFloor(range, date + EPOCH_JULIAN, true);
    return (long) f * MILLIS_PER_DAY;
  }

  public static long unixDateFloor(TimeUnitRange range, long date) {
    return julianDateFloor(range, (int) date + EPOCH_JULIAN, true);
  }

  public static long unixTimestampCeil(TimeUnitRange range, long timestamp) {
    int date = (int) (timestamp / MILLIS_PER_DAY);
    final int f = julianDateFloor(range, date + EPOCH_JULIAN, false);
    return (long) f * MILLIS_PER_DAY;
  }

  public static long unixDateCeil(TimeUnitRange range, long date) {
    return julianDateFloor(range, (int) date + EPOCH_JULIAN, true);
  }

  private static int julianDateFloor(TimeUnitRange range, int julian,
      boolean floor) {
    // this shifts the epoch back to astronomical year -4800 instead of the
    // start of the Christian era in year AD 1 of the proleptic Gregorian
    // calendar.
    int j = julian + 32044;
    int g = j / 146097;
    int dg = j % 146097;
    int c = (dg / 36524 + 1) * 3 / 4;
    int dc = dg - c * 36524;
    int b = dc / 1461;
    int db = dc % 1461;
    int a = (db / 365 + 1) * 3 / 4;
    int da = db - a * 365;

    // integer number of full years elapsed since March 1, 4801 BC
    int y = g * 400 + c * 100 + b * 4 + a;
    // integer number of full months elapsed since the last March 1
    int m = (da * 5 + 308) / 153 - 2;
    // number of days elapsed since day 1 of the month
    int d = da - (m + 4) * 153 / 5 + 122;
    int year = y - 4800 + (m + 2) / 12;
    int month = (m + 2) % 12 + 1;
    int day = d + 1;
    switch (range) {
    case YEAR:
      if (!floor && (month > 1 || day > 1)) {
        ++year;
      }
      return ymdToUnixDate(year, 1, 1);
    case MONTH:
      if (!floor && day > 1) {
        ++month;
      }
      return ymdToUnixDate(year, month, 1);
    default:
      throw new AssertionError(range);
    }
  }

  public static int ymdToUnixDate(int year, int month, int day) {
    final int julian = ymdToJulian(year, month, day);
    return julian - EPOCH_JULIAN;
  }

  public static int ymdToJulian(int year, int month, int day) {
    int a = (14 - month) / 12;
    int y = year + 4800 - a;
    int m = month + 12 * a - 3;
    return day + (153 * m + 2) / 5
        + 365 * y
        + y / 4
        - y / 100
        + y / 400
        - 32045;
  }

  public static long unixTimestamp(int year, int month, int day, int hour,
      int minute, int second) {
    final int date = ymdToUnixDate(year, month, day);
    return (long) date * MILLIS_PER_DAY
        + (long) hour * MILLIS_PER_HOUR
        + (long) minute * MILLIS_PER_MINUTE
        + (long) second * MILLIS_PER_SECOND;
  }

  /** Adds a given number of months to a timestamp, represented as the number
   * of milliseconds since the epoch. */
  public static long addMonths(long timestamp, int m) {
    final long millis =
        DateTimeUtils.floorMod(timestamp, DateTimeUtils.MILLIS_PER_DAY);
    timestamp -= millis;
    final long x =
        addMonths((int) (timestamp / DateTimeUtils.MILLIS_PER_DAY), m);
    return x * DateTimeUtils.MILLIS_PER_DAY + millis;
  }

  /** Adds a given number of months to a date, represented as the number of
   * days since the epoch. */
  public static int addMonths(int date, int m) {
    int y0 = (int) DateTimeUtils.unixDateExtract(TimeUnitRange.YEAR, date);
    int m0 = (int) DateTimeUtils.unixDateExtract(TimeUnitRange.MONTH, date);
    int d0 = (int) DateTimeUtils.unixDateExtract(TimeUnitRange.DAY, date);
    int y = m / 12;
    y0 += y;
    m0 += m - y * 12;
    int last = lastDay(y0, m0);
    if (d0 > last) {
      d0 = 1;
      if (++m0 > 12) {
        m0 = 1;
        ++y0;
      }
    }
    return DateTimeUtils.ymdToUnixDate(y0, m0, d0);
  }

  private static int lastDay(int y, int m) {
    switch (m) {
    case 2:
      return y % 4 == 0
          && (y % 100 != 0
          || y % 400 == 0)
          ? 29 : 28;
    case 4:
    case 6:
    case 9:
    case 11:
      return 30;
    default:
      return 31;
    }
  }

  /** Finds the number of months between two dates, each represented as the
   * number of days since the epoch. */
  public static int subtractMonths(int date0, int date1) {
    if (date0 < date1) {
      return -subtractMonths(date1, date0);
    }
    // Start with an estimate.
    // Since no month has more than 31 days, the estimate is <= the true value.
    int m = (date0 - date1) / 31;
    for (;;) {
      int date2 = addMonths(date1, m);
      if (date2 >= date0) {
        return m;
      }
      int date3 = addMonths(date1, m + 1);
      if (date3 > date0) {
        return m;
      }
      ++m;
    }
  }

  public static int subtractMonths(long t0, long t1) {
    final long millis0 =
        DateTimeUtils.floorMod(t0, DateTimeUtils.MILLIS_PER_DAY);
    final int d0 = (int) DateTimeUtils.floorDiv(t0 - millis0,
        DateTimeUtils.MILLIS_PER_DAY);
    final long millis1 =
        DateTimeUtils.floorMod(t1, DateTimeUtils.MILLIS_PER_DAY);
    final int d1 = (int) DateTimeUtils.floorDiv(t1 - millis1,
        DateTimeUtils.MILLIS_PER_DAY);
    int x = subtractMonths(d0, d1);
    final long d2 = addMonths(d1, x);
    if (d2 == d0 && millis0 < millis1) {
      --x;
    }
    return x;
  }

  /** Divide, rounding towards negative infinity. */
  public static long floorDiv(long x, long y) {
    long r = x / y;
    // if the signs are different and modulo not zero, round down
    if ((x ^ y) < 0 && (r * y != x)) {
      r--;
    }
    return r;
  }

  /** Modulo, always returning a non-negative result. */
  public static long floorMod(long x, long y) {
    return x - floorDiv(x, y) * y;
  }

  /** Creates an instance of {@link Calendar} in the root locale and UTC time
   * zone. */
  public static Calendar calendar() {
    return Calendar.getInstance(UTC_ZONE, Locale.ROOT);
  }

  /** Returns whether a value is an {@code OffsetDateTime}. */
  public static boolean isOffsetDateTime(Object o) {
    return OFFSET_DATE_TIME_HANDLER.isOffsetDateTime(o);
  }

  /** Returns the value of a {@code OffsetDateTime} as a string. */
  public static String offsetDateTimeValue(Object o) {
    return OFFSET_DATE_TIME_HANDLER.stringValue(o);
  }

  public static long formattedStringToUnixDate(String timestamp, String pattern) {
    int length = pattern.length();
    int timestampPosition = 0;
    long resultTimestamp = 0;
    for (int position = 0; position < length; position++) {
      int endPosition = getTokenEndPosition(pattern, position);
      int tokenLen = endPosition - position + 1;
      if (tokenLen <= 0) {
        break;
      }
      char c = pattern.charAt(position);
      int oldStartPosition = position;
      position += tokenLen - 1;
      switch (c) {
      case 'M':
        switch (tokenLen) {
        case 1:
          if (lookAhead(pattern, position, 'I')) {
            // 'MI': Minute (0-59).
            int minute =
                Integer.valueOf(timestamp.substring(timestampPosition, timestampPosition + 2));
            if (minute < 0 || minute > 59) {
              throw new IllegalArgumentException("");
            }
            resultTimestamp += minute * MILLIS_PER_MINUTE;
          } else if (lookAhead(pattern, position, 'S')) {
            // 'MS': Millisecond (000-9999).
            int millis =
                Integer.valueOf(timestamp.substring(timestampPosition, timestampPosition + 3));
            if (millis < 0 || millis > 9999) {
              throw new IllegalArgumentException("");
            }
            resultTimestamp += millis;
          } else {
            throw new IllegalArgumentException("Illegal pattern component: " + c);
          }
          position++;
          break;
          // 'MM': Month (01-12; January = 01).
        case 2:
          int month =
              Integer.valueOf(timestamp.substring(timestampPosition, timestampPosition + 2));
          if (month > 12 || month < 0) {
            throw new IllegalArgumentException("MM should be between 1 and 12");
          }
          resultTimestamp +=
              Integer.valueOf(timestamp.substring(timestampPosition, timestampPosition + 2))
                  * MILLIS_PER_HOUR ;
          timestampPosition += 2;
          position += 2;
          break;
        default:
     //     int2(result, month);
          position = position - (tokenLen - 2);
        }
        break;
      case 'H':
        switch (tokenLen) {
        case 1:
          System.out.println(timestampPosition + " " + pattern.substring(timestampPosition));
          throw new IllegalArgumentException("Illegal pattern component: " + c);
        case 2:
          if (pattern.regionMatches(position + 1, "24", 0, "24".length())) {
            // 'HH24': Hour of day (0-23).
            int hh24 =
                Integer.valueOf(timestamp.substring(timestampPosition, timestampPosition + 2));
            if (hh24 > 23 || hh24 < 0) {
              throw new IllegalArgumentException("HH24 should be between 0 and 23");
            }
            resultTimestamp +=
                Integer.valueOf(timestamp.substring(timestampPosition, timestampPosition + 2))
                    * MILLIS_PER_HOUR;
            timestampPosition += 2;
            position += 2;
          } else if (pattern.regionMatches(position + 1, "12", 0, "12".length())) {
            // 'HH12': Hour of day (1-12).
            int hh12 =
                Integer.valueOf(timestamp.substring(timestampPosition, timestampPosition + 2));
            if (hh12 > 12 || hh12 < 1) {
              throw new IllegalArgumentException("HH24 should be between 0 and 12");
            }
            resultTimestamp +=
                Integer.valueOf(timestamp.substring(timestampPosition, timestampPosition + 2))
                    * MILLIS_PER_HOUR;
            timestampPosition += 2;
            position += 2;
          } else {
            // 'HH': Hour of day (1-12).
            resultTimestamp +=
                Integer.valueOf(timestamp.substring(timestampPosition, timestampPosition + 2))
                    * MILLIS_PER_HOUR;
            timestampPosition += 2;
          }
          break;
        default:
        }
        break;
      default:
        System.out.println(timestamp.substring(timestampPosition));
      }
    }
    System.out.println("before");
    return resultTimestamp;
  }

  public static String unixDateTimeToFormattedString(long timestamp, String pattern) {
    int length = pattern.length();
    StringBuilder result = new StringBuilder();
    int date = (int) (timestamp / MILLIS_PER_DAY);
    int time = (int) (timestamp % MILLIS_PER_DAY);
    if (time < 0) {
      --date;
      time += MILLIS_PER_DAY;
    }
    int julian = date + EPOCH_JULIAN;
    int year = (int) DateTimeUtils.unixDateExtract(TimeUnitRange.YEAR, date);
    int month = (int) DateTimeUtils.unixDateExtract(TimeUnitRange.MONTH, date);
    int day = (int) DateTimeUtils.unixDateExtract(TimeUnitRange.DAY, date);
    int hour = DateTimeUtils.unixTimeExtract(TimeUnitRange.HOUR, time);
    int minute = DateTimeUtils.unixTimeExtract(TimeUnitRange.MINUTE, time);
    int second = DateTimeUtils.unixTimeExtract(TimeUnitRange.SECOND, time);

    for (int position = 0; position < length; position++) {
      int endPosition = getTokenEndPosition(pattern, position);
      int tokenLen = endPosition - position + 1;
      if (tokenLen <= 0) {
        break;
      }
      char c = pattern.charAt(position);
      int oldStartPosition = position;
      position += tokenLen - 1;
      boolean isUpperCase = false;
      switch (c) {
      // Meridian indicator with or without periods.
      case 'A': // 'AM', 'A.M.', 'AD', 'A.D.'
      case 'B': // 'BC', 'B.C.'
      case 'P': // 'PM', 'P.M.'
        isUpperCase = true;
      //fall through
      case 'a': // 'am', 'a.m.', 'ad', 'a.d.'
      case 'b': // 'pm', 'p.m.'
      case 'p': // 'pm', 'p.m.'
        if ((c == 'b' || c == 'B')
            && (pattern.regionMatches(true, position + 1, ".c.", 0, ".c.".length()))) {
          result
              .append(year > 0 ? isUpperCase ? "A.D." : "a.d." : isUpperCase ? "B.C." : "b.c.");
          position += 3;
        } else if (pattern.regionMatches(true, position + 1, ".m.", 0, ".m.".length())) {
          result
              .append(hour > 11 ? isUpperCase ? "P.M." : "p.m." : isUpperCase ? "A.M." : "a.m.");
          position += 3;
        } else if (pattern.regionMatches(true, position + 1, ".d.", 0, ".d.".length())) {
          result
              .append(year > 0 ? isUpperCase ? "A.D." : "a.d." : isUpperCase ? "B.C." : "b.c.");
          position += 3;
        } else if (pattern.regionMatches(true, position + 1, "m", 0, "m".length())) {
          result.append(hour > 11 ? isUpperCase ? "PM" : "pm" : isUpperCase ? "AM" : "am");
          position++;
        } else if (pattern.regionMatches(true, position + 1, "d", 0, "d".length())
            || pattern.regionMatches(true, position + 1, "c", 0, "c".length())) {
          result.append(year > 0 ? isUpperCase ? "AD" : "ad" : isUpperCase ? "BC" : "bc");
          position++;
        } else {
          throw new IllegalArgumentException(
              "Illegal pattern component: " + c + " at " + position);
        }
        break;
      case 'Y': // year designator (text)
        switch (tokenLen) {
        case 1:
          // 'Y,YYY': Year with comma in this position. E.g.: 2,018.
          if (pattern.regionMatches(position + 1, ",YYY", 0, ",YYY".length())) {
            result.append(year / 1000).append(',');
            int3(result, year);
            position += 4;
          } else { // 'Y': Last 1 digit of year.
            result.append(year % 10);
          }
          break;
        case 2: // 'YY': Last 2 digits of year.
          int2(result, year);
          break;
        case 3: // 'YYY': Last 3 digits of year.
          int3(result, year);
          break;
        case 4: // 'YYYY': 4 digits of year.
          int4(result, year);
          break;
        default:
          result.append(year);
          position = position - (tokenLen - 4);
        }
        break;
      case 'D': // day designator (text)
        switch (tokenLen) {
        // 'D': Day of week (1-7) where sun=1, sat=7.
        case 1:
          result.append(DateTimeUtils.unixDateExtract(TimeUnitRange.DOW, date));
          break;
        // 'DD': Day of month (1-31).
        case 2:
          result.append(day);
          break;
        // 'DDD': Day of year (1-366).
        case 3:
          result.append(DateTimeUtils.unixDateExtract(TimeUnitRange.DOY, date));
          break;
        default:
          result.append(DateTimeUtils.unixDateExtract(TimeUnitRange.DOY, date));
          position = position - (tokenLen - 3);
        }
        break;
      // month, minute, millisecond designator
      case 'M':
        switch (tokenLen) {
        case 1:
          if (lookAhead(pattern, position, 'I')) {
            // 'MI': Minute (0-59).
            result.append(minute);
          } else if (lookAhead(pattern, position, 'S')) {
            // 'MS': Millisecond (000-9999).
            int3(result, time % 1000);
          } else {
            throw new IllegalArgumentException("Illegal pattern component: " + c);
          }
          position++;
          break;
        // 'MM': Month (01-12; January = 01).
        case 2:
          int2(result, month);
          break;
        default:
          int2(result, month);
          position = position - (tokenLen - 2);
        }
        break;
      // second designator
      case 'S':
        switch (tokenLen) {
        case 1:
          throw new IllegalArgumentException("Illegal pattern component: " + c);
        // 'SS': Second (0-59).
        case 2:
          result.append(second);
          break;
        case 3:
          // as S is not valid => 3 also not a right option
          throw new IllegalArgumentException("Illegal pattern component: "
              + pattern.substring(oldStartPosition, oldStartPosition + tokenLen));
        // Seconds past midnight (0-86399).
        case 4: // 'SSSS':  PostgreSQL case
        case 5: // 'SSSSS': Oracle case
          result.append(time / MILLIS_PER_SECOND);
          break;
        default:
          result.append(time / MILLIS_PER_SECOND);
          position = position - (tokenLen - 5);
        }
        break;
      // 'Q': Quarter of year (1, 2, 3, 4; January - March = 1).
      case 'Q':
        for (int k = 0; k < tokenLen; k++) {
          result.append(DateTimeUtils.unixDateExtract(TimeUnitRange.QUARTER, date));
        }
        break;
      // 'CC': Century.
      case 'C':
        switch (tokenLen) {
        case 1:
          throw new IllegalArgumentException("Illegal pattern component: " + c);
        case 2:
          result.append(DateTimeUtils.unixDateExtract(TimeUnitRange.CENTURY, date));
          break;
        default:
          result.append(DateTimeUtils.unixDateExtract(TimeUnitRange.CENTURY, date));
          position = position - (tokenLen - 2);
        }
        break;
      // Week designator
      case 'W':
        switch (tokenLen) {
        // 'W': Week of month (1-5) where week 1 starts on
        // the first day of the month and ends on the seventh.
        case 1:
          result.append(day / 7 + 1);
          break;
        // 'WW': Week of year (1-53) where week 1 starts on
        // the first day of the year and continues to the seventh day of the year.
        case 2:
          result.append(getWeekOfYear(julian, year));
          break;
        default:
          result.append(getWeekOfYear(julian, year));
          position = position - (tokenLen - 2);
        }
        break;
      // Hour designator
      case 'H':
        switch (tokenLen) {
        case 1:
          throw new IllegalArgumentException("Illegal pattern component: " + c);
        case 2:
          if (pattern.regionMatches(position + 1, "24", 0,"24".length())) {
            // 'HH24': Hour of day (0-23).
            result.append(hour);
            position += 2;
          } else if (pattern.regionMatches(position + 1, "12", 0, "12".length())) {
            // 'HH12': Hour of day (1-12).
            result.append(hour % 12);
            position += 2;
          } else {
            // 'HH': Hour of day (1-12).
            result.append(hour == 0 ? 12 : hour % 12);
          }
          break;
        default:
          result.append(hour == 0 ? 12 : hour % 12);
          position = position - (tokenLen - 2);
        }
        break;
      // '-', '/', ',', '.', ';', ':', ' ': Punctuation text is reproduced in the result.
      case ':':
      case '-':
      case ',':
      case '.':
      case ';':
      case '/':
      case ' ':
        result.append(pattern, oldStartPosition, oldStartPosition + tokenLen);
        break;
      // '"': Quoted text is reproduced in the result.
      case '\"':
        result.append(pattern, oldStartPosition + 1, oldStartPosition + tokenLen - 1);
        break;
      // 'J': Julian day; the number of days since January 1, 4712 BC.
      // Number specified with J must be integers.
      case 'J':
        for (int k = 0; k < tokenLen; k++) {
          result.append(julian);
        }
        break;
      // 'RM': Roman numeral month (I-XII; January = I).
      // 'rm': Roman numeral month (i-xii; January = i).
      case 'R':
        isUpperCase = true;
        //fall through
      case 'r':
        switch (tokenLen) {
        case 1:
          if ((isUpperCase && lookAhead(pattern, position, 'M'))
              || (!isUpperCase && lookAhead(pattern, position, 'm'))) {
            String monthInRomean = getMonthInRoman(month);
            result.append(isUpperCase ? monthInRomean.toUpperCase(Locale.ROOT) : monthInRomean);
            position++;
            break;
          } else {
            throw new IllegalArgumentException("Illegal pattern component: "
                + c + pattern.charAt(oldStartPosition));
          }
        default:
          throw new IllegalArgumentException("Illegal pattern component: "
              + pattern.substring(oldStartPosition, oldStartPosition + tokenLen));
        }
        break;
      // ISO specific designator
      case 'I':
        int iso8601WeekNumber = getIso8601WeekNumber(julian, year, month, day);
        int iso8601Year = getIso8601Year(year, month, iso8601WeekNumber);
        if (tokenLen == 1 && position + tokenLen < length) {
          position++;
          endPosition = getTokenEndPosition(pattern, position);
          tokenLen = endPosition - position + 1;
          if (tokenLen > 0) {
            c = pattern.charAt(position);
            oldStartPosition = position;
            position += tokenLen - 1;
            switch (c) {
            case 'Y':
              switch (tokenLen) {
              // 'IY': Last 2 digits of ISO year.
              case 1:
                int2(result, iso8601Year % 100);
                break;
              // 'IYY': Last 3 digits of ISO year.
              case 2:
                int3(result, iso8601Year % 1000);
                break;
              // 'IYYY': 4 digits of ISO year.
              case 3:
                int4(result, iso8601Year);
                break;
              default:
                result.append(iso8601Year);
                position = position - (tokenLen - 3);
              }
              break;
            case 'W':
              switch (tokenLen) {
              // 'IW': Week of year (1-52 or 1-53) based on the ISO standard.
              case 1:
                result.append(iso8601WeekNumber);
                break;
              default:
                result.append(iso8601WeekNumber);
                position = position - (tokenLen - 1);
              }
              break;
            case 'D':
              long isodow = DateTimeUtils.unixDateExtract(TimeUnitRange.ISODOW, date);
              switch (tokenLen) {
              // 'ID': ISO 8601 day of the week, Monday (1) to Sunday (7)
              case 1:
                result.append(isodow);
                break;
              case 2:
                throw new IllegalArgumentException("Illegal pattern component: "
                    + pattern.substring(oldStartPosition, oldStartPosition + tokenLen));
              // 'IDDD': Day of ISO 8601 week-numbering year
              // (001-371; day 1 of the year is Monday of the first ISO week)
              case 3:
                result.append((iso8601WeekNumber - 1) * 7 + isodow);
                break;
              default:
                result.append(isodow);
                position = position - (tokenLen - 3);
              }
              break;
            default:
              // 'I': Last digit of ISO year (each letter 'I' one digit repeat)
              result.append(iso8601Year % 10);
              position = oldStartPosition - 1;
            }
          } else {
            // 'I': Last digit of ISO year (each letter 'I' one digit repeat)
            result.append(iso8601Year % 10);
            position--;
          }
        } else {
        // 'I': Last digit of ISO year (each letter 'I' one digit repeat)
          for (int k = 0; k < tokenLen; k++) {
            result.append(iso8601Year % 10);
          }
        }
        break;
      default:
        throw new IllegalArgumentException("Illegal pattern component: "
            + pattern.substring(oldStartPosition, oldStartPosition + tokenLen));
      }
    }
    return result.toString();
  }

  private static String getMonthInRoman(int month) {
    switch (month) {
    case 1:
      return "i";
    case 2:
      return "ii";
    case 3:
      return "iii";
    case 4:
      return "iv";
    case 5:
      return "v";
    case 6:
      return "vi";
    case 7:
      return "vii";
    case 8:
      return "viii";
    case 9:
      return "ix";
    case 10:
      return "x";
    case 11:
      return "xi";
    case 12:
      return "xii";
    default:
      throw new AssertionError("Wrong month's number " + month);
    }
  }

  private static boolean lookAhead(String pattern, int startPosition, char first) {
    return startPosition + 1 < pattern.length()
        && pattern.charAt(startPosition + 1) == first;
  }

  public static int getTokenEndPosition(String pattern, int indexRef) {
    int i = indexRef;
    int length = pattern.length();

    char c = pattern.charAt(i);
    if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z'
            || c == ':' || c == '.' || c == ',' || c == ';' || c == '-' || c == '/' || c == ' ') {
      // Scan a run of the same character
      while (i + 1 < length) {
        char peek = pattern.charAt(i + 1);
        if (peek == c) {
          i++;
        } else {
          break;
        }
      }
    } else {
      // This will identify token as text.
      boolean inLiteral = false;

      for (; i < length; i++) {
        c = pattern.charAt(i);
        if (c == '\"') {
          if (i + 1 < length && pattern.charAt(i + 1) == '\"') {
            // "" is treated as escaped "
            i++;
          } else {
            inLiteral = !inLiteral;
          }
        } else if (!inLiteral
                && ((c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z')
                || c == ':' || c == '.' || c == ',' || c == ';'
                || c == '-' || c == '/' || c == ' ')) {
          i--;
          break;
        }
      }
    }
    return i;
  }

  //~ Inner Classes ----------------------------------------------------------

  /**
   * Helper class for {@link DateTimeUtils#parsePrecisionDateTimeLiteral}
   */
  public static class PrecisionTime {
    private final Calendar cal;
    private final String fraction;
    private final int precision;

    public PrecisionTime(Calendar cal, String fraction, int precision) {
      this.cal = cal;
      this.fraction = fraction;
      this.precision = precision;
    }

    public Calendar getCalendar() {
      return cal;
    }

    public int getPrecision() {
      return precision;
    }

    public String getFraction() {
      return fraction;
    }
  }

  /** Deals with values of {@code java.time.OffsetDateTime} without introducing
   * a compile-time dependency (because {@code OffsetDateTime} is only JDK 8 and
   * higher). */
  private interface OffsetDateTimeHandler {
    boolean isOffsetDateTime(Object o);
    String stringValue(Object o);
  }

  /** Implementation of {@code OffsetDateTimeHandler} for environments where
   * no instances are possible. */
  private static class NoopOffsetDateTimeHandler
      implements OffsetDateTimeHandler {
    public boolean isOffsetDateTime(Object o) {
      return false;
    }

    public String stringValue(Object o) {
      throw new UnsupportedOperationException();
    }
  }

  /** Implementation of {@code OffsetDateTimeHandler} for environments where
   * no instances are possible. */
  private static class ReflectiveOffsetDateTimeHandler
      implements OffsetDateTimeHandler {
    final Class offsetDateTimeClass;

    private ReflectiveOffsetDateTimeHandler() throws ClassNotFoundException {
      offsetDateTimeClass = Class.forName("java.time.OffsetDateTime");
    }

    public boolean isOffsetDateTime(Object o) {
      return o != null && o.getClass() == offsetDateTimeClass;
    }

    public String stringValue(Object o) {
      return o.toString();
    }
  }
}

// End DateTimeUtils.java
