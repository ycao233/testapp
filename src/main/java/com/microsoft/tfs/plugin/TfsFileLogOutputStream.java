package com.microsoft.tfs.plugin;

import hudson.console.ConsoleNote;
import hudson.console.LineTransformationOutputStream;
import hudson.console.PlainTextConsoleOutputStream;

import java.io.*;
import java.util.logging.Logger;

/**
 * Created by yacao on 12/17/2014.
 */
public class TfsFileLogOutputStream extends LineTransformationOutputStream {

    private static final Logger logger = Logger.getLogger(TfsFileLogOutputStream.class.getName());

    private final OutputStream delegate;

    private final PrintWriter pw;

    public TfsFileLogOutputStream(final OutputStream delegate, OutputStream log) throws FileNotFoundException {
        this.delegate = delegate;

        this.pw = new PrintWriter(new BufferedOutputStream(log)) ;
        logger.info("Initialized TFS logger");
    }

    protected void eol(byte[] b, int len) throws IOException {
        delegate.write(b, 0, len);

        String line = ConsoleNote.removeNotes(new String(b, 0, len)).trim();
        this.pw.println(line);
    }

    public void flush() throws IOException {
        delegate.flush();
        this.pw.flush();
    }

    public void close() throws IOException {
        delegate.close();
        this.pw.close();
    }
}
