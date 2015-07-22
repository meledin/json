package com.github.meledin.json;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.OptionalDouble;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

import flexjson.JSONDeserializer;

public class JSONParser {

	interface Generator {
		/**
		 * Returns true iff the character <i>c</i> is a valid terminator for
		 * this type.
		 * 
		 * @param c
		 * @return
		 */
		boolean isTerminator(int c);

		boolean explicitExit();

		/**
		 * Creates the object (or returns the generated object value).
		 * 
		 * @return
		 */
		Object generate();

		void accept(Object item, int c);
	}

	static class ListGenerator implements Generator {
		enum State {
			BEGIN, EXPECT_VALUE, EXPECT_COMMA
		}

		State state = State.BEGIN;
		ArrayList<Object> list = new ArrayList<>();

		public void accept(Object item, int c) {
			switch (state) {
			case EXPECT_COMMA:
				if (c != ',')
					throw new IllegalArgumentException("Expected comma");
				state = State.EXPECT_VALUE;
				break;
			case BEGIN:
			case EXPECT_VALUE:
				list.add(item);
				state = State.EXPECT_COMMA;
				break;
			}
		}

		@Override
		public boolean isTerminator(int c) {
			if (c == ']') {
				if (state == State.EXPECT_VALUE)
					throw new IllegalArgumentException("Cannot end an array when a value is expected");
				return true;
			}
			return false;
		}

		@Override
		public Object generate() {
			return list;
		}

		@Override
		public boolean explicitExit() {
			return true;
		}
	}

	static class MapGenerator implements Generator {
		HashMap<String, Object> map = new HashMap<>();

		enum State {
			BEGIN, EXPECT_KEY, EXPECT_COLON, EXPECT_VALUE, EXPECT_COMMA
		}

		State state = State.BEGIN;
		String key = null;

		public void accept(Object item, int c) {
			switch (state) {
			case BEGIN:
			case EXPECT_KEY:
				key = (String) item;
				state = State.EXPECT_COLON;
				break;
			case EXPECT_COLON:
				if (c != ':')
					throw new IllegalArgumentException("Colon expected in map");
				state = State.EXPECT_VALUE;
				break;
			case EXPECT_VALUE:
				map.put(key, item);
				state = State.EXPECT_COMMA;
				break;
			case EXPECT_COMMA:
				if (c != ',')
					throw new IllegalArgumentException("Comma expected in map");
				state = State.EXPECT_KEY;
				break;
			}
		}

		@Override
		public boolean isTerminator(int c) {
			if (c == '}') {

				if (state == State.EXPECT_COLON)
					throw new IllegalArgumentException("Cannot end object; expected colon");
				if (state == State.EXPECT_VALUE)
					throw new IllegalArgumentException("Cannot end object; expected value");
				if (state == State.EXPECT_KEY)
					throw new IllegalArgumentException("Cannot end object; expected key");
				return true;
			}
			return false;
		}

		@Override
		public Object generate() {
			return map;
		}

		@Override
		public boolean explicitExit() {
			return true;
		}
	}

	static class StringGenerator implements Generator {

		StringBuilder numBuf;
		StringBuilder buf;
		int endChar = '"';
		int escapeMode = START;
		int unicodeChar = 0;

		static final int START = -1;
		static final int NONE = 0;
		static final int ESCAPED = 1;
		static final int HEX1 = 1001;
		static final int HEX2 = 1002;
		static final int UNICODE1 = 2001;
		static final int UNICODE2 = 2002;
		static final int UNICODE3 = 2003;
		static final int UNICODE4 = 2004;

		StringGenerator(StringBuilder buf, StringBuilder numBuf) {
			this.buf = buf;
			this.numBuf = numBuf;
			buf.setLength(0);
			numBuf.setLength(0);
		}

		@Override
		public void accept(Object t, int c) {

			switch (escapeMode) {

			case NONE:
				if ('\\' == c)
					escapeMode = ESCAPED;
				else
					buf.appendCodePoint(c);
				break;

			case START:
				endChar = c;
				escapeMode = NONE;
				break;

			case ESCAPED:
				switch (c) {
				case 'b':
					buf.append('\b');
					break;
				case 't':
					buf.append('\t');
					break;
				case 'n':
					buf.append('\n');
					break;
				case 'f':
					buf.append('\f');
					break;
				case 'r':
					buf.append('\r');
					break;
				case 'u':
					escapeMode = UNICODE1;
					break;
				case 'x':
					escapeMode = HEX1;
					break;
				default:
					buf.appendCodePoint(c);
				}

			case UNICODE1:
			case UNICODE2:
			case UNICODE3:
			case HEX1:
				escapeMode++;
				numBuf.appendCodePoint(c);
				break;

			case HEX2:
			case UNICODE4:
				buf.appendCodePoint((char) Integer.parseInt(numBuf.toString(), 16));
				numBuf.setLength(0);
				escapeMode = NONE;
				break;

			}

		}

		@Override
		public boolean isTerminator(int c) {
			return endChar == c && escapeMode == NONE;
		}

		@Override
		public Object generate() {
			return buf.toString();
		}

		@Override
		public boolean explicitExit() {
			return true;
		}

	}

	static class ConstantGenerator implements Generator {

		int idx = 0;
		Object rv = null;
		String targetStr;

		ConstantGenerator() {
		}

		@Override
		public boolean isTerminator(int c) {
			return Character.isWhitespace(c) || ":[]{},".indexOf(c) > -1;
		}

		@Override
		public Object generate() {
			return rv;
		}

		@Override
		public void accept(Object t, int c) {
			c = Character.toLowerCase(c);
			if (idx == 0) {
				if ('t' == c) {
					rv = true;
					targetStr = "true";
				} else if ('f' == c) {
					rv = false;
					targetStr = "false";
				} else if ('n' == c) {
					rv = null;
					targetStr = "null";
				}
			} else {
				if (targetStr.charAt(idx) != c)
					throw new IllegalArgumentException("Expected " + targetStr.charAt(idx) + " but found " + c);
			}
			idx++;
		}

		@Override
		public boolean explicitExit() {
			return false;
		}

	}

	static class NumberGenerator implements Generator {

		private StringBuilder buf;

		NumberGenerator(StringBuilder buf) {
			this.buf = buf;
			buf.setLength(0);
		}

		@Override
		public boolean isTerminator(int c) {
			return "0123456789.eE+-".indexOf(c) == -1;
		}

		@Override
		public Object generate() {
			return new NumberHolder(buf.toString());
		}

		@Override
		public void accept(Object t, int c) {
			buf.appendCodePoint(c);
		}

		@Override
		public boolean explicitExit() {
			return false;
		}

	}

	static class Parser<T> {

		ArrayDeque<Generator> stack = new ArrayDeque<>(32);
		Generator curr = null;
		StringBuilder buf = new StringBuilder(64);
		StringBuilder numBuf = new StringBuilder(64);
		boolean stringParsingMode = false;
		T rv;

		T parse(String str) {

			NumberGenerator nums = new NumberGenerator(buf);
			StringGenerator strings = new StringGenerator(buf, numBuf);

			stack.push(new Generator() {

				@SuppressWarnings("unchecked")
				@Override
				public void accept(Object t, int c) {

					if (t == null && c > -1)
						throw new IllegalArgumentException("Cannot have more text at the root level");

					rv = (T) t;
				}

				@Override
				public boolean isTerminator(int c) {
					return false;
				}

				@Override
				public Object generate() {
					return null;
				}

				@Override
				public boolean explicitExit() {
					return true;
				}
			});
			
			curr = stack.peek();
			
			chars: for (char c : str.toCharArray()) {
				while (curr.isTerminator(c)) {
					stringParsingMode = false;
					Generator gen = stack.pop();
					curr = stack.peek();
					Object object = gen.generate();
					curr.accept(object, -1);
					if (gen.explicitExit())
						continue chars;
				}

				if (stringParsingMode) {
					curr.accept(null, c);
					continue;
				}

				if (Character.isWhitespace(c)) {
					continue;
				}

				switch (c) {
				case ':':
				case ',':
					curr.accept(null, c);
					break;

				case '{':
					stack.push(curr = new MapGenerator());
					break;

				case '[':
					stack.push(curr = new ListGenerator());
					break;

				case '"':
				case '\'':
					stringParsingMode = true;
					stack.push(curr = strings);
					buf.setLength(0);
					numBuf.setLength(0);
					curr.accept(null, c);
					break;

				case '0':
				case '1':
				case '2':
				case '3':
				case '4':
				case '5':
				case '6':
				case '7':
				case '8':
				case '9':
					stringParsingMode = true;
					stack.push(curr = nums);
					buf.setLength(0);
					curr.accept(null, c);
					break;

				default:
					stringParsingMode = true;
					stack.push(curr = new ConstantGenerator());
					curr.accept(null, c);
					break;
				}

			}

			return rv;

		}

	}

	public static <T> T parse(String str) {

		Parser<T> parser = new Parser<T>();
		return parser.parse(str);
	}

	public static void main(String[] args) throws IOException {
		StringBuffer sb = new StringBuffer();
		Files.readAllLines(Paths.get("/tmp/catalog2.json")).forEach(line -> sb.append(line));
		String src = sb.toString();

		/*
		 * for (int i = 0; i < 500; i++) { long start =
		 * System.currentTimeMillis(); new
		 * JSONDeserializer<>().deserialize(src); if (i % 100 == 0)
		 * System.out.println(System.currentTimeMillis() - start); }
		 */

		OptionalDouble average = LongStream.range(0, 2500).parallel().map(l -> {
			long start = System.currentTimeMillis();
			parse(src);
			//new JSONDeserializer<>().deserialize(src);
			return System.currentTimeMillis() - start;
		}).skip(500).average();

		System.out.println(average.getAsDouble());

		average = LongStream.range(0, 2500).parallel().map(l -> {
			long start = System.currentTimeMillis();
			new JSONDeserializer<>().deserialize(src);
			return System.currentTimeMillis() - start;
		}).skip(500).average();

		System.err.println(average.getAsDouble());
		
	}

}
