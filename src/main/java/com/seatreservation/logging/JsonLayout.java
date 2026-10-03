package com.seatreservation.logging;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.IThrowableProxy;
import ch.qos.logback.classic.spi.ThrowableProxyUtil;
import ch.qos.logback.core.LayoutBase;

import java.time.Instant;

/** Logback layout that prints each event as one JSON line, including everything in the MDC (requestId ...). */
public class JsonLayout extends LayoutBase<ILoggingEvent> {

    @Override
    public String doLayout(ILoggingEvent event) {
        IThrowableProxy throwable = event.getThrowableProxy();
        return JsonLines.format(
                Instant.ofEpochMilli(event.getTimeStamp()).toString(),
                event.getLevel().toString(),
                event.getLoggerName(),
                event.getFormattedMessage(),
                event.getMDCPropertyMap(),
                throwable == null ? null : ThrowableProxyUtil.asString(throwable));
    }
}
