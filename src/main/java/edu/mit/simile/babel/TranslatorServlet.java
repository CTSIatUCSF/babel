package edu.mit.simile.babel;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.codec.net.URLCodec;
import org.apache.commons.lang.StringUtils;
import org.apache.log4j.Logger;
import org.openrdf.sail.Sail;
import org.openrdf.sail.SailInitializationException;
import org.openrdf.sail.memory.MemoryStore;

import com.oreilly.servlet.multipart.FilePart;
import com.oreilly.servlet.multipart.MultipartParser;
import com.oreilly.servlet.multipart.Part;

import edu.mit.simile.babel.type.SemanticType;

public class TranslatorServlet extends HttpServlet {
	final static private long serialVersionUID = 2083937775584527297L;
	final static private Logger s_logger = Logger.getLogger(TranslatorServlet.class);
	
	@Override
	protected void doPost(HttpServletRequest request, HttpServletResponse response) 
			throws ServletException, IOException {
		
		Properties 		readerProperties = new Properties();
		Properties 		writerProperties = new Properties();
		String			readerName = null;
		String			writerName = null;
		List<String>	urls = new ArrayList<String>();
		boolean			bodyIsFile = false;
		
		/*
		 * Parse parameters
		 */
        String[] params = StringUtils.splitPreserveAllTokens(request.getQueryString(), '&');
        for (int i = 0; i < params.length; i++) {
            String param = params[i];
            int equalIndex = param.indexOf('=');

            if (equalIndex >= 0) {
                String rawName = param.substring(0, equalIndex);
                String rawValue = param.substring(equalIndex + 1);

                String name = decode(rawName);
                String value = decode(rawValue);

				if (name.startsWith("in-")) {
					readerProperties.setProperty(name.substring(3), value);
				} else if (name.startsWith("out-")) {
					writerProperties.setProperty(name.substring(4), value);
				} else if (name.equals("url")) {
					urls.add(value);
				} else if (name.equals("reader")) {
					readerName = value;
				} else if (name.equals("writer")) {
					writerName = value;
				} else if (name.equals("body") && value.equals("file")) {
					bodyIsFile = true;
				}
            }
		}
		
		/*
		 * Instantiate converters
		 */
		if (readerName == null) {
			s_logger.warn("No reader name in request");
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			return;
		} else if (writerName == null) {
			s_logger.warn("No writer name in request");
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			return;
		}
		
		BabelConverter reader = Babel.s_converters.get(readerName); 
		BabelConverter writer = Babel.s_converters.get(writerName); 
		if (reader == null) {
			s_logger.warn("No reader of name " + readerName);
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			return;
		} else if (writer == null) {
			s_logger.warn("No writer of name " + writerName);
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			return;
		}
		
		/*
		 * Check compatibility
		 */
		SemanticType readerType = reader.getSemanticType();
		SemanticType writerType = writer.getSemanticType();
		if (!writerType.getClass().isInstance(readerType)) {
			s_logger.warn(
				"Writer " + writerType.getClass().getName() + 
				" cannot take input from reader " + readerType.getClass().getName()
			);
			response.setStatus(HttpServletResponse.SC_BAD_REQUEST);
			return;
		}
		
		/*
		 * Read in data, convert, and write result out
		 */
		MemoryStore store = new MemoryStore();
		try {
			store.initialize();
			try {
				if (bodyIsFile) {
					readFiles(reader, store, readerProperties, request);
				} else {
					readRequestBody(reader, store, readerProperties, request);
				}
				
				writeResult(writer, store, writerProperties, response);
			} catch (BabelException e) {
				response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
			} finally {
				store.shutDown();
			}
		} catch (SailInitializationException e) {
			s_logger.error(e);
			response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
		}
	}
	
	@SuppressWarnings("unchecked")
	protected Enumeration<String> getParameterNames(HttpServletRequest request) {
		return (Enumeration<String>) request.getParameterNames();
	}
	
	protected void readRequestBody(
		BabelConverter 		converter,
		Sail				sail,
		Properties			readerProperties,
		HttpServletRequest	request
	) throws BabelException {
		try {
			converter.read(request.getReader(), sail, readerProperties);
		} catch (Exception e) {
			s_logger.error(e);
			throw new BabelException(e);
		}
	}

	protected void readFiles(
		BabelConverter 		converter,
		Sail				sail,
		Properties			readerProperties,
		HttpServletRequest	request
	) throws BabelException {
		try {
			MultipartParser parser = new MultipartParser(request, 5 * 1024 * 1024);
			
			Part part = null;
			while ((part = parser.readNextPart()) != null) {
				if (part.isFile()) {
					FilePart filePart = (FilePart) part;
					Reader reader = new InputStreamReader(filePart.getInputStream());
					try {
						converter.read(reader, sail, readerProperties);
					} finally {
						reader.close();
					}
				}
			}
		} catch (Exception e) {
			s_logger.error(e);
			throw new BabelException(e);
		}
	}
	
	protected void readURLs(
		BabelConverter 		converter,
		Sail				sail,
		Properties			readerProperties,
		List<String>		urls
	) throws BabelException {
		try {
			Iterator<String> i = urls.iterator();
			while (i.hasNext()) {
				URLConnection connection = new URL(i.next()).openConnection();
				connection.setConnectTimeout(5000);
				connection.connect();
				
				Reader reader = new InputStreamReader(
					connection.getInputStream(), connection.getContentEncoding());
				try {
					converter.read(reader, sail, readerProperties);
				} finally {
					reader.close();
				}
			}
		} catch (Exception e) {
			s_logger.error(e);
			throw new BabelException(e);
		}
	}

	protected void writeResult(
		BabelConverter 		writer, 
		Sail 				sail, 
		Properties 			writerProperties,
		HttpServletResponse response
	) throws BabelException {
		response.setCharacterEncoding("UTF-8");
		response.setContentType(writer.getSerializationFormat().getMimetype());
		
		try {
			Writer bufferedWriter = new BufferedWriter(
					new OutputStreamWriter(response.getOutputStream(), "UTF-8"));
			try {
				writer.write(bufferedWriter, sail, writerProperties);
			} finally {
				bufferedWriter.close();
			}
		} catch (Exception e) {
			s_logger.error(e);
			throw new BabelException(e);
		}
	}
	
    private static final String s_urlEncoding = "UTF-8";
    private static final URLCodec s_codec = new URLCodec();
    
    static public String decode(String s) {
        try {
            return s_codec.decode(s, s_urlEncoding);
        } catch (Exception e) {
            throw new RuntimeException("Exception decoding " + s + " with " + s_urlEncoding + " encoding.");
        }
    }
}
