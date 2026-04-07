package tool

// StringProp returns a JSON Schema property definition for a string.
func StringProp(description string) map[string]any {
	return map[string]any{
		"type":        "string",
		"description": description,
	}
}

// IntProp returns a JSON Schema property definition for an integer.
func IntProp(description string) map[string]any {
	return map[string]any{
		"type":        "integer",
		"description": description,
	}
}

// BoolProp returns a JSON Schema property definition for a boolean.
func BoolProp(description string) map[string]any {
	return map[string]any{
		"type":        "boolean",
		"description": description,
	}
}

// ArrayProp returns a JSON Schema property definition for an array of strings.
func ArrayProp(description string) map[string]any {
	return map[string]any{
		"type":        "array",
		"items":       map[string]any{"type": "string"},
		"description": description,
	}
}

// SchemaBuilder builds a JSON Schema object definition with required/optional properties.
type SchemaBuilder struct {
	properties map[string]map[string]any
	required   []string
}

// ObjectSchema creates a new SchemaBuilder for an object schema.
func ObjectSchema() *SchemaBuilder {
	return &SchemaBuilder{
		properties: make(map[string]map[string]any),
	}
}

// Required adds a required property to the schema.
func (b *SchemaBuilder) Required(name string, schema map[string]any) *SchemaBuilder {
	b.properties[name] = schema
	b.required = append(b.required, name)
	return b
}

// Optional adds an optional property to the schema.
func (b *SchemaBuilder) Optional(name string, schema map[string]any) *SchemaBuilder {
	b.properties[name] = schema
	return b
}

// Build returns the completed JSON Schema object.
func (b *SchemaBuilder) Build() map[string]any {
	props := make(map[string]any, len(b.properties))
	for k, v := range b.properties {
		props[k] = v
	}

	schema := map[string]any{
		"type":       "object",
		"properties": props,
	}
	if len(b.required) > 0 {
		req := make([]string, len(b.required))
		copy(req, b.required)
		schema["required"] = req
	}
	return schema
}
