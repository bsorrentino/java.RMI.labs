package org.bsc.rmi.proxy.http.client;

import lombok.extern.java.Log;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import static java.lang.String.format;

/**
 * The HttpSendOutputStream class is used by the HttpSendSocket class as
 * a layer on the top of the OutputStream it returns so that it can be
 * notified of attempts to write to it.  This allows the HttpSendSocket
 * to know when it should construct a new message.
 */
@Log
class HttpSendOutputStream extends FilterOutputStream {

    /** the HttpSendSocket object that is providing this stream */
    final HttpSendSocket owner;

    /**
     * Create new filter on a given output stream.
     * @param out the OutputStream to filter from
     * @param owner the HttpSendSocket that is providing this stream
     */
    public HttpSendOutputStream(OutputStream out, HttpSendSocket owner)
        throws IOException
    {
        super(out);

        this.owner = owner;
    }

    /**
     * Mark this stream as inactive for its owner socket, so the next time
     * a write is attempted, the owner will be notified and a new underlying
     * output stream obtained.
     */
    public void deactivate()
    {
        out = null;
    }

    /**
     * Write a byte of data to the stream.
     */
    public void write(int b) throws IOException
    {

        if (out == null)
            out = owner.writeNotify();

        log.info( format("write [%d] [%c]", b, b) );

        out.write(b);
    }

    /**
     * Write a subarray of bytes.
     * @param b the buffer from which the data is to be written
     * @param off the start offset of the data
     * @param len the number of bytes to be written
     */
    public void write(byte b[], int off, int len) throws IOException
    {
        if (len == 0) return;

        if (out == null)
            out = owner.writeNotify();

        log.info( format("write( bytes, [%d] [%d]", off, len) );

        out.write(b, off, len);
    }

    /**
     * Flush the stream.
     */
    public void flush() throws IOException
    {
        if (out != null)
            out.flush();
    }

    /**
     * Close the stream.
     */
    public void close() throws IOException
    {
        flush();
        owner.close();
    }
}
