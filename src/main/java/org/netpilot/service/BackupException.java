package org.netpilot.service;

import java.io.IOException;
import org.netpilot.i18n.Messages;

/** A backup that cannot be used; nothing was changed. The message is for the user. */
public final class BackupException extends IOException {

    private static final long serialVersionUID = 1L;

    /** Why the backup cannot be used. */
    public enum Kind {
        /** Not a NetPilot backup at all. */
        NOT_A_BACKUP,
        /** Made by a newer NetPilot with a format this one does not know. */
        NEWER_FORMAT,
        /** Encrypted: a password is needed. */
        NEEDS_PASSWORD,
        /** The password is wrong, or the encrypted file was changed. */
        WRONG_PASSWORD,
        /** A file listed in the backup is missing. */
        INCOMPLETE,
        /** A file's fingerprint (SHA-256) or size does not match: damaged or changed. */
        CORRUPT
    }

    private final Kind kind;

    public BackupException(Kind kind, Object... details) {
        super(Messages.get("backup.error." + kind.name(), details));
        this.kind = kind;
    }

    public BackupException(Kind kind, Throwable cause) {
        super(Messages.get("backup.error." + kind.name(), ""), cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
