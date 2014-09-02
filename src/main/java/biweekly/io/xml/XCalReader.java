package biweekly.io.xml;

import static biweekly.io.xml.XCalNamespaceContext.XCAL_NS;
import static biweekly.io.xml.XCalQNames.COMPONENTS;
import static biweekly.io.xml.XCalQNames.ICALENDAR;
import static biweekly.io.xml.XCalQNames.PARAMETERS;
import static biweekly.io.xml.XCalQNames.PROPERTIES;
import static biweekly.io.xml.XCalQNames.VCALENDAR;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import javax.xml.namespace.QName;
import javax.xml.transform.ErrorListener;
import javax.xml.transform.Source;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.stream.StreamSource;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import biweekly.ICalVersion;
import biweekly.ICalendar;
import biweekly.Warning;
import biweekly.component.ICalComponent;
import biweekly.component.VTimezone;
import biweekly.io.CannotParseException;
import biweekly.io.ParseContext;
import biweekly.io.ParseContext.TimezonedDate;
import biweekly.io.ParseWarnings;
import biweekly.io.SkipMeException;
import biweekly.io.TimezoneInfo;
import biweekly.io.scribe.ScribeIndex;
import biweekly.io.scribe.component.ICalComponentScribe;
import biweekly.io.scribe.property.ICalPropertyScribe;
import biweekly.parameter.ICalParameters;
import biweekly.property.ICalProperty;
import biweekly.property.TimezoneId;
import biweekly.property.Version;
import biweekly.property.Xml;
import biweekly.util.ICalDateFormat;
import biweekly.util.XmlUtils;

/*
 Copyright (c) 2013, Michael Angstadt
 All rights reserved.

 Redistribution and use in source and binary forms, with or without
 modification, are permitted provided that the following conditions are met: 

 1. Redistributions of source code must retain the above copyright notice, this
 list of conditions and the following disclaimer. 
 2. Redistributions in binary form must reproduce the above copyright notice,
 this list of conditions and the following disclaimer in the documentation
 and/or other materials provided with the distribution. 

 THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.

 The views and conclusions contained in the software and documentation are those
 of the authors and should not be interpreted as representing official policies, 
 either expressed or implied, of the FreeBSD Project.
 */

/**
 * <p>
 * Reads xCals (XML-encoded vCards) in a streaming fashion.
 * </p>
 * <p>
 * <b>Example:</b>
 * 
 * <pre class="brush:java">
 * File file = new File(&quot;xcals.xml&quot;);
 * List&lt;ICalendar&gt; icals = new ArrayList&lt;ICalendar&gt;();
 * XCalReader xcalReader = new XCalReader(file);
 * ICalendar ical;
 * while ((ical = xcalReader.readNext()) != null) {
 * 	icals.add(ical);
 * }
 * </pre>
 * 
 * </p>
 * @author Michael Angstadt
 * @see <a href="http://tools.ietf.org/html/rfc6321">RFC 6321</a>
 */
public class XCalReader implements Closeable {
	private final Source source;
	private final Closeable stream;

	private volatile ICalendar readICal;
	private final ParseWarnings warnings = new ParseWarnings();
	private TimezoneInfo tzinfo;
	private volatile TransformerException thrown;
	private volatile ScribeIndex index = new ScribeIndex();
	private ParseContext context;

	private final ReadThread thread = new ReadThread();
	private final Object lock = new Object();
	private final BlockingQueue<Object> readerBlock = new ArrayBlockingQueue<Object>(1);
	private final BlockingQueue<Object> threadBlock = new ArrayBlockingQueue<Object>(1);

	/**
	 * Creates an xCal reader.
	 * @param str the string to read the xCals from
	 */
	public XCalReader(String str) {
		this(new StringReader(str));
	}

	/**
	 * Creates an xCal reader.
	 * @param in the input stream to read the xCals from
	 */
	public XCalReader(InputStream in) {
		source = new StreamSource(in);
		stream = in;
	}

	/**
	 * Creates an xCal reader.
	 * @param file the file to read the xCals from
	 * @throws FileNotFoundException if the file doesn't exist
	 */
	public XCalReader(File file) throws FileNotFoundException {
		this(new FileInputStream(file));
	}

	/**
	 * Creates an xCal reader.
	 * @param reader the reader to read from
	 */
	public XCalReader(Reader reader) {
		source = new StreamSource(reader);
		stream = reader;
	}

	/**
	 * Creates an xCal reader.
	 * @param node the DOM node to read from
	 */
	public XCalReader(Node node) {
		source = new DOMSource(node);
		stream = null;
	}

	/**
	 * <p>
	 * Registers a component scribe. This is the same as calling:
	 * </p>
	 * <p>
	 * {@code getScribeIndex().register(scribe)}
	 * </p>
	 * @param scribe the scribe to register
	 */
	public void registerScribe(ICalComponentScribe<? extends ICalComponent> scribe) {
		index.register(scribe);
	}

	/**
	 * <p>
	 * Registers a property scribe. This is the same as calling:
	 * </p>
	 * <p>
	 * {@code getScribeIndex().register(scribe)}
	 * </p>
	 * @param scribe the scribe to register
	 */
	public void registerScribe(ICalPropertyScribe<? extends ICalProperty> scribe) {
		index.register(scribe);
	}

	/**
	 * Gets the scribe index.
	 * @return the scribe index
	 */
	public ScribeIndex getScribeIndex() {
		return index;
	}

	/**
	 * Sets the scribe index.
	 * @param index the scribe index
	 */
	public void setScribeIndex(ScribeIndex index) {
		this.index = index;
	}

	/**
	 * Gets the warnings from the last iCalendar object that was unmarshalled.
	 * This list is reset every time a new iCalendar object is read.
	 * @return the warnings or empty list if there were no warnings
	 */
	public List<String> getWarnings() {
		return warnings.copy();
	}

	/**
	 * Gets the timezone info of the last iCalendar object that was read. This
	 * object is recreated every time a new iCalendar object is read.
	 * @return the timezone info
	 */
	public TimezoneInfo getTimezoneInfo() {
		return tzinfo;
	}

	/**
	 * Reads the next iCalendar object from the xCal stream.
	 * @return the next iCalendar object or null if there are no more
	 * @throws TransformerException if there's a problem reading from the stream
	 */
	public ICalendar readNext() throws TransformerException {
		readICal = null;
		warnings.clear();
		context = new ParseContext();
		tzinfo = new TimezoneInfo();
		thrown = null;

		if (!thread.started) {
			thread.start();
		} else {
			if (thread.finished || thread.closed) {
				return null;
			}

			try {
				threadBlock.put(lock);
			} catch (InterruptedException e) {
				return null;
			}
		}

		//wait until thread reads xCard
		try {
			readerBlock.take();
		} catch (InterruptedException e) {
			return null;
		}

		if (thrown != null) {
			throw thrown;
		}

		if (readICal == null) {
			return null;
		}

		//handle timezones
		for (Map.Entry<String, List<TimezonedDate>> entry : context.getTimezonedDates()) {
			//find the VTIMEZONE component with the given TZID
			String tzid = entry.getKey();
			VTimezone component = null;
			for (VTimezone vtimezone : readICal.getTimezones()) {
				TimezoneId timezoneId = vtimezone.getTimezoneId();
				if (timezoneId != null && tzid.equals(timezoneId.getValue())) {
					component = vtimezone;
					break;
				}
			}

			TimeZone timezone = null;
			if (component == null) {
				//A VTIMEZONE component couldn't found
				//so treat the TZID parameter value as an Olsen timezone ID
				timezone = ICalDateFormat.parseTimeZoneId(tzid);
				if (timezone == null) {
					warnings.add(null, null, Warning.parse(38, tzid));
				} else {
					warnings.add(null, null, Warning.parse(37, tzid));
				}
			} else {
				//TODO convert the VTIMEZONE component to a Java TimeZone object
				//TODO for now, treat the TZID as an Olsen timezone (which is what biweekly used to do) 
				timezone = ICalDateFormat.parseTimeZoneId(tzid);
				if (timezone == null) {
					timezone = TimeZone.getDefault();
				}
			}

			if (timezone == null) {
				//timezone could not be determined
				continue;
			}

			//assign this VTIMEZONE component to the TimeZone object
			tzinfo.assign(component, timezone);

			List<TimezonedDate> timezonedDates = entry.getValue();
			for (TimezonedDate timezonedDate : timezonedDates) {
				//assign the property to the timezone
				ICalProperty property = timezonedDate.getProperty();
				tzinfo.setTimezone(property, timezone);
				property.getParameters().setTimezoneId(null); //remove the TZID parameter

				//parse the date string again under its real timezone
				Date realDate = ICalDateFormat.parse(timezonedDate.getDateStr(), timezone);

				//update the Date object with the new timestamp
				timezonedDate.getDate().setTime(realDate.getTime()); //the one time I am glad that Date objects are mutable... xD
			}
		}

		for (ICalProperty property : context.getFloatingDates()) {
			tzinfo.setUseFloatingTime(property, true);
		}

		return readICal;
	}

	private class ReadThread extends Thread {
		private final SAXResult result;
		private final Transformer transformer;
		private volatile boolean finished = false, started = false, closed = false;

		public ReadThread() {
			setName(getClass().getSimpleName());

			//create the transformer
			try {
				transformer = TransformerFactory.newInstance().newTransformer();
			} catch (TransformerConfigurationException e) {
				//no complex configurations
				throw new RuntimeException(e);
			}

			//prevent error messages from being printed to stderr
			transformer.setErrorListener(new ErrorListener() {
				public void error(TransformerException e) {
					//empty
				}

				public void fatalError(TransformerException e) {
					//empty
				}

				public void warning(TransformerException e) {
					//empty
				}
			});

			result = new SAXResult(new ContentHandlerImpl());
		}

		@Override
		public void run() {
			started = true;

			try {
				transformer.transform(source, result);
			} catch (TransformerException e) {
				if (!thread.closed) {
					thrown = e;
				}
			} finally {
				finished = true;
				try {
					readerBlock.put(lock);
				} catch (InterruptedException e) {
					//ignore
				}
			}
		}
	}

	private class ContentHandlerImpl extends DefaultHandler {
		private final Document DOC = XmlUtils.createDocument();
		private final XCalStructure structure = new XCalStructure();
		private final StringBuilder characterBuffer = new StringBuilder();
		private final LinkedList<ICalComponent> componentStack = new LinkedList<ICalComponent>();

		private Element propertyElement, parent;
		private QName paramName;
		private ICalComponent curComponent;
		private ICalParameters parameters;

		@Override
		public void characters(char[] buffer, int start, int length) throws SAXException {
			characterBuffer.append(buffer, start, length);
		}

		@Override
		public void startElement(String namespace, String localName, String qName, Attributes attributes) throws SAXException {
			QName qname = new QName(namespace, localName);
			String textContent = characterBuffer.toString();
			characterBuffer.setLength(0);

			if (structure.isEmpty()) {
				//<icalendar>
				if (ICALENDAR.equals(qname)) {
					structure.push(ElementType.icalendar);
				}
				return;
			}

			ElementType parentType = structure.peek();
			ElementType typeToPush = null;
			//System.out.println(structure.stack + " current: " + localName);
			if (parentType != null) {
				switch (parentType) {

				case icalendar:
					//<vcalendar>
					if (VCALENDAR.equals(qname)) {
						ICalComponentScribe<? extends ICalComponent> scribe = index.getComponentScribe(localName);
						ICalComponent component = scribe.emptyInstance();

						curComponent = component;
						readICal = (ICalendar) component;
						typeToPush = ElementType.component;
					}
					break;

				case component:
					if (PROPERTIES.equals(qname)) {
						//<properties>
						typeToPush = ElementType.properties;
					} else if (COMPONENTS.equals(qname)) {
						//<components>
						componentStack.add(curComponent);
						curComponent = null;

						typeToPush = ElementType.components;
					}
					break;

				case components:
					//start component element
					if (XCAL_NS.equals(namespace)) {
						ICalComponentScribe<? extends ICalComponent> scribe = index.getComponentScribe(localName);
						curComponent = scribe.emptyInstance();

						ICalComponent parent = componentStack.getLast();
						parent.addComponent(curComponent);

						typeToPush = ElementType.component;
					}
					break;

				case properties:
					//start property element
					propertyElement = createElement(namespace, localName, attributes);
					parameters = new ICalParameters();
					parent = propertyElement;
					typeToPush = ElementType.property;
					break;

				case property:
					//<parameters>
					if (PARAMETERS.equals(qname)) {
						typeToPush = ElementType.parameters;
					}
					break;

				case parameters:
					//inside of <parameters>
					if (XCAL_NS.equals(namespace)) {
						paramName = qname;
						typeToPush = ElementType.parameter;
					}
					break;

				case parameter:
					//inside of a parameter element
					if (XCAL_NS.equals(namespace)) {
						typeToPush = ElementType.parameterValue;
					}
					break;
				case parameterValue:
					//should never have child elements
					break;
				}
			}

			//append element to property element
			if (propertyElement != null && typeToPush != ElementType.property && typeToPush != ElementType.parameters && !structure.isUnderParameters()) {
				if (textContent.length() > 0) {
					parent.appendChild(DOC.createTextNode(textContent));
				}

				Element element = createElement(namespace, localName, attributes);
				parent.appendChild(element);
				parent = element;
			}

			structure.push(typeToPush);
		}

		@Override
		public void endElement(String namespace, String localName, String qName) throws SAXException {
			String textContent = characterBuffer.toString();
			characterBuffer.setLength(0);

			if (structure.isEmpty()) {
				//no <icalendar> elements were read yet
				return;
			}

			ElementType type = structure.pop();
			if (type == null && (propertyElement == null || structure.isUnderParameters())) {
				//it's a non-xCal element
				return;
			}

			//System.out.println(structure.stack + " ending: " + localName);
			if (type != null) {
				switch (type) {
				case parameterValue:
					parameters.put(paramName.getLocalPart(), textContent);
					break;

				case parameter:
					//do nothing
					break;

				case parameters:
					//do nothing
					break;

				case property:
					context.getWarnings().clear();

					propertyElement.appendChild(DOC.createTextNode(textContent));

					//unmarshal property and add to parent component
					QName propertyQName = new QName(propertyElement.getNamespaceURI(), propertyElement.getLocalName());
					String propertyName = localName;
					ICalPropertyScribe<? extends ICalProperty> scribe = index.getPropertyScribe(propertyQName);
					try {
						ICalProperty property = scribe.parseXml(propertyElement, parameters, context);
						if (property instanceof Version && curComponent instanceof ICalendar) {
							Version versionProp = (Version) property;
							ICalVersion version = versionProp.toICalVersion();
							if (version != null) {
								ICalendar ical = (ICalendar) curComponent;
								ical.setVersion(version);
								context.setVersion(version);

								propertyElement = null;
								break;
							}
						}

						curComponent.addProperty(property);
						for (Warning warning : context.getWarnings()) {
							warnings.add(null, propertyName, warning);
						}
					} catch (SkipMeException e) {
						warnings.add(null, propertyName, 22, e.getMessage());
					} catch (CannotParseException e) {
						String xml = XmlUtils.toString(propertyElement);
						warnings.add(null, propertyName, 33, xml, e.getMessage());

						scribe = index.getPropertyScribe(Xml.class);
						ICalProperty property = scribe.parseXml(propertyElement, parameters, context);
						curComponent.addProperty(property);
					}

					propertyElement = null;
					break;

				case component:
					curComponent = null;

					//</vcalendar>
					if (VCALENDAR.getNamespaceURI().equals(namespace) && VCALENDAR.getLocalPart().equals(localName)) {
						//wait for readNext() to be called again
						try {
							readerBlock.put(lock);
							threadBlock.take();
						} catch (InterruptedException e) {
							throw new SAXException(e);
						}
						return;
					}
					break;

				case properties:
					break;

				case components:
					curComponent = componentStack.removeLast();
					break;

				case icalendar:
					break;
				}
			}

			//append element to property element
			if (propertyElement != null && type != ElementType.property && type != ElementType.parameters && !structure.isUnderParameters()) {
				if (textContent.length() > 0) {
					parent.appendChild(DOC.createTextNode(textContent));
				}
				parent = (Element) parent.getParentNode();
			}
		}

		private Element createElement(String namespace, String localName, Attributes attributes) {
			Element element = DOC.createElementNS(namespace, localName);

			//copy the attributes
			for (int i = 0; i < attributes.getLength(); i++) {
				String qname = attributes.getQName(i);
				if (qname.startsWith("xmlns:")) {
					continue;
				}

				String name = attributes.getLocalName(i);
				String value = attributes.getValue(i);
				element.setAttribute(name, value);
			}

			return element;
		}
	}

	private enum ElementType {
		//a value is missing for "vcalendar" because it is treated as a "component"
		//enum values are lower-case so they won't get confused with the "XCalQNames" variable names
		icalendar, components, properties, component, property, parameters, parameter, parameterValue;
	}

	/**
	 * <p>
	 * Keeps track of the structure of an xCal XML document.
	 * </p>
	 * 
	 * <p>
	 * Note that this class is here because you can't just do QName comparisons
	 * on a one-by-one basis. The location of an XML element within the XML
	 * document is important too. It's possible for two elements to have the
	 * same QName, but be treated differently depending on their location (e.g.
	 * the {@code <duration>} property has a {@code <duration>} data type)
	 * </p>
	 */
	private class XCalStructure {
		private final List<ElementType> stack = new ArrayList<ElementType>();

		/**
		 * Pops the top element type off the stack.
		 * @return the element type or null if the stack is empty
		 */
		public ElementType pop() {
			return stack.isEmpty() ? null : stack.remove(stack.size() - 1);
		}

		/**
		 * Looks at the top element type.
		 * @return the top element type or null if the stack is empty
		 */
		public ElementType peek() {
			return stack.isEmpty() ? null : stack.get(stack.size() - 1);
		}

		/**
		 * Adds an element type to the stack.
		 * @param type the type to add or null if the XML element is not an xCal
		 * element
		 */
		public void push(ElementType type) {
			stack.add(type);
		}

		/**
		 * Determines if the leaf node is under a {@code <parameters>} element.
		 * @return true if it is, false if not
		 */
		public boolean isUnderParameters() {
			//get the first non-null type
			ElementType nonNull = null;
			for (int i = stack.size() - 1; i >= 0; i--) {
				ElementType type = stack.get(i);
				if (type != null) {
					nonNull = type;
					break;
				}
			}

			return nonNull == ElementType.parameters || nonNull == ElementType.parameter || nonNull == ElementType.parameterValue;
		}

		/**
		 * Determines if the stack is empty
		 * @return true if the stack is empty, false if not
		 */
		public boolean isEmpty() {
			return stack.isEmpty();
		}
	}

	/**
	 * Closes the underlying input stream.
	 */
	public void close() throws IOException {
		if (thread.isAlive()) {
			thread.closed = true;
			thread.interrupt();
		}

		if (stream != null) {
			stream.close();
		}
	}
}
