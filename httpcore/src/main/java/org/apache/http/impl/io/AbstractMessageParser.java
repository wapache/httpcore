/*
 * $HeadURL$
 * $Revision$
 * $Date$
 *
 * ====================================================================
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 * ====================================================================
 *
 * This software consists of voluntary contributions made by many
 * individuals on behalf of the Apache Software Foundation.  For more
 * information on the Apache Software Foundation, please see
 * <http://www.apache.org/>.
 *
 */

package org.apache.http.impl.io;

import java.io.IOException;
import java.util.ArrayList;

import org.apache.http.Header;
import org.apache.http.HttpException;
import org.apache.http.HttpMessage;
import org.apache.http.ParseException;
import org.apache.http.ProtocolException;
import org.apache.http.io.HttpMessageParser;
import org.apache.http.io.SessionInputBuffer;
import org.apache.http.message.LineParser;
import org.apache.http.message.BasicLineParser;
import org.apache.http.params.CoreConnectionPNames;
import org.apache.http.params.HttpParams;
import org.apache.http.util.CharArrayBuffer;

/**
 * Abstract base class for HTTP message parsers that obtain input from 
 * an instance of {@link SessionInputBuffer}. 
 * 
 * @since 4.0
 */
public abstract class AbstractMessageParser implements HttpMessageParser {

    private final SessionInputBuffer sessionBuffer;
    private final int maxHeaderCount;
    private final int maxLineLen;
    protected final LineParser lineParser;


    /**
     * Creates an instance of this class.
     * <p>
     * The following HTTP parameters affect the initialization:
     * <p>
     * {@link CoreConnectionPNames#MAX_HEADER_COUNT} parameter determines 
     * the maximum HTTP header count allowed. If set to a positive value, 
     * the number of HTTP headers received from the data stream exceeding 
     * this limit will cause an IOException. A negative or zero value will 
     * effectively disable the check. Per default the check is disabled. 
     * <p>
     * {@link CoreConnectionPNames#MAX_LINE_LENGTH} parameter determines 
     * the maximum line length limit. If set to a positive value, any HTTP line 
     * exceeding this limit will cause an IOException. A negative or zero value
     * will effectively disable the check the check. Per default the check is 
     * disabled.
     * 
     * @param buffer the session input buffer.
     * @param parser the line parser.
     * @param params HTTP parameters.
     */
    public AbstractMessageParser(
            final SessionInputBuffer buffer,
            final LineParser parser,
            final HttpParams params) {
        super();
        if (buffer == null) {
            throw new IllegalArgumentException("Session input buffer may not be null");
        }
        if (params == null) {
            throw new IllegalArgumentException("HTTP parameters may not be null");
        }
        this.sessionBuffer = buffer;
        this.maxHeaderCount = params.getIntParameter(
                CoreConnectionPNames.MAX_HEADER_COUNT, -1);
        this.maxLineLen = params.getIntParameter(
                CoreConnectionPNames.MAX_LINE_LENGTH, -1);
        this.lineParser = (parser != null) ? parser : BasicLineParser.DEFAULT;
    }

    /**
     * Parses HTTP headers from the data receiver stream according to the generic 
     * format as given in Section 3.1 of RFC 822, RFC-2616 Section 4 and 19.3.
     *  
     * @param inbuffer Session input buffer
     * @param maxHeaderCount maximum number of headers allowed. If the number
     *  of headers received from the data stream exceeds maxCount value, an
     *  IOException will be thrown. Setting this parameter to a negative value
     *  or zero will disable the check.
     * @param maxLineLen maximum number of characters for a header line,
     *  including the continuation lines. Setting this parameter to a negative 
     *  value or zero will disable the check.
     * @return array of HTTP headers
     * 
     * @throws IOException in case of an I/O error
     * @throws HttpException in case of HTTP protocol violation
     */
    public static Header[] parseHeaders(
            final SessionInputBuffer inbuffer,
            int maxHeaderCount,
            int maxLineLen,
            LineParser parser)
        throws HttpException, IOException {

        if (inbuffer == null) {
            throw new IllegalArgumentException("Session input buffer may not be null");
        }
        if (parser == null)
            parser = BasicLineParser.DEFAULT;

        ArrayList headerLines = new ArrayList();

        CharArrayBuffer current = null;
        CharArrayBuffer previous = null;
        for (;;) {
            if (current == null) {
                current = new CharArrayBuffer(64);
            } else {
                current.clear();
            }
            int l = inbuffer.readLine(current);
            if (l == -1 || current.length() < 1) {
                break;
            }
            // Parse the header name and value
            // Check for folded headers first
            // Detect LWS-char see HTTP/1.0 or HTTP/1.1 Section 2.2
            // discussion on folded headers
            if ((current.charAt(0) == ' ' || current.charAt(0) == '\t') && previous != null) {
                // we have continuation folded header
                // so append value
                int i = 0;
                while (i < current.length()) {
                    char ch = current.charAt(i);
                    if (ch != ' ' && ch != '\t') {
                        break;
                    }
                    i++;
                }
                if (maxLineLen > 0 
                        && previous.length() + 1 + current.length() - i > maxLineLen) {
                    throw new IOException("Maximum line length limit exceeded");
                }
                previous.append(' ');
                previous.append(current, i, current.length() - i);
            } else {
                headerLines.add(current);
                previous = current;
                current = null;
            }
            if (maxHeaderCount > 0 && headerLines.size() >= maxHeaderCount) {
                throw new IOException("Maximum header count exceeded");
            }
        }
        Header[] headers = new Header[headerLines.size()];
        for (int i = 0; i < headerLines.size(); i++) {
            CharArrayBuffer buffer = (CharArrayBuffer) headerLines.get(i);
            try {
                headers[i] = parser.parseHeader(buffer);
            } catch (ParseException ex) {
                throw new ProtocolException(ex.getMessage());
            }
        }
        return headers;
    }

    /**
     * Subclasses must override this method to generate an instance of 
     * {@link HttpMessage} based on the initial input from the session buffer.
     * <p>
     * Usually this method is expected to read just the very first line or 
     * the very first valid from the data stream and based on the input generate 
     * an appropriate instance of {@link HttpMessage}.
     * 
     * @param sessionBuffer the session input buffer.
     * @return HTTP message based on the input from the session buffer.
     * @throws IOException in case of an I/O error.
     * @throws HttpException in case of HTTP protocol violation.
     * @throws ParseException in case of a parse error.
     */
    protected abstract HttpMessage parseHead(SessionInputBuffer sessionBuffer) 
        throws IOException, HttpException, ParseException;

    public HttpMessage parse() throws IOException, HttpException {
        HttpMessage message = null;
        try {
            message = parseHead(this.sessionBuffer);
        } catch (ParseException px) {
            throw new ProtocolException(px.getMessage(), px);
        }
        Header[] headers = AbstractMessageParser.parseHeaders(
                this.sessionBuffer, 
                this.maxHeaderCount,
                this.maxLineLen,
                this.lineParser);
        message.setHeaders(headers);
        return message;
    }
    
}
