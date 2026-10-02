package org.example.llm;

public final class Json {

    private Json() {}

    public static String quote(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\t' -> sb.append("\\t");
                case '\r' -> { }
                default   -> sb.append(c);
            }
        }
        return sb.append('"').toString();
    }

    public static String extractString(String json, String field) {
        String key = "\"" + field + "\":\"";
        int i = json.indexOf(key);
        if (i < 0) return json;

        StringBuilder sb = new StringBuilder();
        for (int k = i + key.length(); k < json.length(); k++) {
            char c = json.charAt(k);
            if (c == '\\') {
                char n = json.charAt(++k);
                if (n == 'u') {
                    sb.append((char) Integer.parseInt(json.substring(k + 1, k + 5), 16));
                    k += 4;
                } else {
                    sb.append(switch (n) {
                        case 'n' -> '\n';
                        case 't' -> '\t';
                        case 'r' -> '\r';
                        case '"' -> '"';
                        case '\\' -> '\\';
                        case '/' -> '/';
                        default  -> n;
                    });
                }
            } else if (c == '"') break;
            else sb.append(c);
        }
        return sb.toString();
    }
}