package com.sonnet.wyf.gitreport.workflow.mybatissqlreview;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class MyBatisXmlSourceDecoder {
    private static final Pattern XML_DECLARED_ENCODING = Pattern.compile(
            "(?is)^\\uFEFF?\\s*<\\?xml\\b[^?]*\\bencoding\\s*=\\s*(['\"])([^'\"]+)\\1");

    private MyBatisXmlSourceDecoder() {
    }

    static String decodeUtf8(byte[] bytes, String relativePath) {
        String source;
        try {
            source = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException ex) {
            throw new IllegalArgumentException("invalid UTF-8 in MyBatis mapper XML: " + relativePath, ex);
        }

        Matcher encoding = XML_DECLARED_ENCODING.matcher(source);
        if (encoding.find()) {
            String declared = encoding.group(2).trim();
            boolean utf8;
            try {
                utf8 = StandardCharsets.UTF_8.equals(Charset.forName(declared));
            } catch (RuntimeException ex) {
                utf8 = false;
            }
            if (!utf8) {
                throw new IllegalArgumentException("MyBatis mapper XML requires UTF-8 but declares '"
                        + declared + "': " + relativePath);
            }
        }
        return source;
    }
}
