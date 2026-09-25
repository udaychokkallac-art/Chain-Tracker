package com.chaintracker.model;

/**
 * Domain model representing an event log entry in the ChainTracker observability engine.
 */
public record EventLog(
        String type,
        String message,
        String time
) {
    public EventLog {
        if (type == null) type = "INFO";
        if (message == null) message = "";
        if (time == null) time = "";
    }

    public String toJson() {
        return String.format(
                "{\"type\":\"%s\", \"message\":\"%s\", \"time\":\"%s\"}",
                escape(type), escape(message), escape(time)
        );
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }
}
