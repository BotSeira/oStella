package xyz.zcraft.ostella.console;

import java.util.ArrayList;
import java.util.List;

final class ConsoleInputParser {
    private ConsoleInputParser() { }

    static ParsedInput parse(String input) {
        String raw = input == null ? "" : input;
        List<String> values = new ArrayList<>();
        int index = 0;
        while (index < raw.length()) {
            while (index < raw.length() && Character.isWhitespace(raw.charAt(index))) index++;
            if (index == raw.length()) break;
            StringBuilder value = new StringBuilder();
            char quote = 0;
            while (index < raw.length()) {
                char current = raw.charAt(index);
                if (quote != 0) {
                    if (current == quote) { quote = 0; index++; }
                    else if (current == '\\' && index + 1 < raw.length()) { value.append(raw.charAt(index + 1)); index += 2; }
                    else { value.append(current); index++; }
                } else if (current == '\'' || current == '"') { quote = current; index++; }
                else if (Character.isWhitespace(current)) break;
                else if (current == '\\' && index + 1 < raw.length()) { value.append(raw.charAt(index + 1)); index += 2; }
                else { value.append(current); index++; }
            }
            if (quote != 0) throw new IllegalArgumentException("Unclosed quote in console command");
            values.add(value.toString());
        }
        return new ParsedInput(List.copyOf(values));
    }

    record ParsedInput(List<String> values) {
        int size() { return values.size(); }
        String value(int index) { return values.get(index); }
    }
}
