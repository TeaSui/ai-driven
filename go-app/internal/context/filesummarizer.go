package context

import (
	"fmt"
	"regexp"
	"strings"
)

// DefaultThreshold is the default character count below which files are returned unchanged.
const DefaultThreshold = 500

// FileSummarizer reduces large source files to structural summaries using regex-based extraction.
// This typically achieves 60-80% token reduction while preserving the structural overview.
type FileSummarizer struct {
	threshold int
}

// NewFileSummarizer creates a FileSummarizer with the given character threshold.
// Files with content length below the threshold are returned unchanged.
func NewFileSummarizer(threshold int) *FileSummarizer {
	if threshold <= 0 {
		threshold = DefaultThreshold
	}
	return &FileSummarizer{threshold: threshold}
}

// Summarize produces a structural summary of the given file content based on the file extension.
// Supported extensions: .java, .ts, .js, .tsx, .jsx, .py, .go.
// Unsupported extensions result in truncation with a notice.
func (f *FileSummarizer) Summarize(content, extension string) string {
	if len(content) <= f.threshold {
		return content
	}

	ext := strings.TrimPrefix(strings.ToLower(extension), ".")
	switch ext {
	case "java":
		return f.summarizeJava(content)
	case "ts", "tsx", "js", "jsx":
		return f.summarizeTypeScript(content)
	case "py":
		return f.summarizePython(content)
	case "go":
		return f.summarizeGo(content)
	default:
		return f.truncateUnsupported(content, ext)
	}
}

// Threshold returns the current character threshold.
func (f *FileSummarizer) Threshold() int {
	return f.threshold
}

// --- Java ---

var (
	javaPackageRe     = regexp.MustCompile(`(?m)^package\s+[^;]+;`)
	javaImportRe      = regexp.MustCompile(`(?m)^import\s+[^;]+;`)
	javaAnnotationRe  = regexp.MustCompile(`(?m)^@\w+(?:\([^)]*\))?`)
	javaClassDeclRe   = regexp.MustCompile(`(?m)^(?:public\s+|protected\s+|private\s+|abstract\s+|final\s+|static\s+)*(?:class|interface|enum|record)\s+\w+[^{]*`)
	javaFieldRe       = regexp.MustCompile(`(?m)^\s+(?:public|protected|private)\s+(?:static\s+)?(?:final\s+)?[\w<>\[\],\s]+\s+\w+\s*[;=]`)
	javaMethodSigRe   = regexp.MustCompile(`(?m)^\s+(?:@\w+(?:\([^)]*\))?\s+)*(?:public|protected|private)?\s*(?:static\s+)?(?:final\s+)?(?:synchronized\s+)?(?:abstract\s+)?[\w<>\[\],\s?]+\s+\w+\s*\([^)]*\)(?:\s*throws\s+[\w,\s]+)?`)
)

func (f *FileSummarizer) summarizeJava(content string) string {
	var sb strings.Builder
	sb.WriteString("// [SUMMARIZED - structural outline]\n\n")

	if m := javaPackageRe.FindString(content); m != "" {
		sb.WriteString(m)
		sb.WriteString("\n\n")
	}

	imports := javaImportRe.FindAllString(content, -1)
	if len(imports) > 0 {
		limit := min(len(imports), 20)
		for _, imp := range imports[:limit] {
			sb.WriteString(imp)
			sb.WriteString("\n")
		}
		if len(imports) > 20 {
			sb.WriteString(fmt.Sprintf("// ... and %d more imports\n", len(imports)-20))
		}
		sb.WriteString("\n")
	}

	annotations := javaAnnotationRe.FindAllString(content, -1)
	for _, ann := range annotations {
		sb.WriteString(ann)
		sb.WriteString("\n")
	}

	classDecls := javaClassDeclRe.FindAllString(content, -1)
	for _, decl := range classDecls {
		sb.WriteString(strings.TrimSpace(decl))
		sb.WriteString(" {\n")
	}

	fields := javaFieldRe.FindAllString(content, -1)
	if len(fields) > 0 {
		sb.WriteString("\n  // Fields\n")
		for _, field := range fields {
			sb.WriteString(strings.TrimRight(strings.TrimSpace(field), "="))
			sb.WriteString(";\n")
		}
	}

	methods := javaMethodSigRe.FindAllString(content, -1)
	if len(methods) > 0 {
		sb.WriteString("\n  // Methods\n")
		for _, method := range methods {
			sb.WriteString("  ")
			sb.WriteString(strings.TrimSpace(method))
			sb.WriteString(" { ... }\n")
		}
	}

	sb.WriteString("}\n")
	return sb.String()
}

// --- TypeScript / JavaScript ---

var (
	tsImportRe    = regexp.MustCompile(`(?m)^import\s+.+`)
	tsInterfaceRe = regexp.MustCompile(`(?m)^(?:export\s+)?(?:interface|type)\s+\w+[^{;]*`)
	tsClassDeclRe = regexp.MustCompile(`(?m)^(?:export\s+)?(?:abstract\s+)?class\s+\w+[^{]*`)
	tsFuncSigRe   = regexp.MustCompile(`(?m)^(?:export\s+)?(?:async\s+)?(?:function\s+\w+|(?:const|let|var)\s+\w+\s*=\s*(?:async\s+)?\([^)]*\)(?:\s*:\s*[\w<>\[\]|&\s]+)?\s*=>|(?:const|let|var)\s+\w+\s*=\s*(?:async\s+)?function)`)
)

func (f *FileSummarizer) summarizeTypeScript(content string) string {
	var sb strings.Builder
	sb.WriteString("// [SUMMARIZED - structural outline]\n\n")

	imports := tsImportRe.FindAllString(content, -1)
	if len(imports) > 0 {
		limit := min(len(imports), 15)
		for _, imp := range imports[:limit] {
			sb.WriteString(imp)
			sb.WriteString("\n")
		}
		if len(imports) > 15 {
			sb.WriteString(fmt.Sprintf("// ... and %d more imports\n", len(imports)-15))
		}
		sb.WriteString("\n")
	}

	interfaces := tsInterfaceRe.FindAllString(content, -1)
	for _, iface := range interfaces {
		sb.WriteString(strings.TrimSpace(iface))
		sb.WriteString(" { ... }\n")
	}

	classes := tsClassDeclRe.FindAllString(content, -1)
	for _, cls := range classes {
		sb.WriteString(strings.TrimSpace(cls))
		sb.WriteString(" { ... }\n")
	}

	funcs := tsFuncSigRe.FindAllString(content, -1)
	if len(funcs) > 0 {
		sb.WriteString("\n// Functions\n")
		for _, fn := range funcs {
			sb.WriteString(strings.TrimSpace(fn))
			sb.WriteString(" { ... }\n")
		}
	}

	return sb.String()
}

// --- Python ---

var (
	pyImportRe   = regexp.MustCompile(`(?m)^(?:import|from)\s+.+`)
	pyClassRe    = regexp.MustCompile(`(?m)^class\s+\w+[^:]*:`)
	pyFuncRe     = regexp.MustCompile(`(?m)^(?:    |\t)?(?:async\s+)?def\s+\w+\s*\([^)]*\)(?:\s*->\s*[\w\[\],\s|]+)?:`)
	pyDocstringRe = regexp.MustCompile(`(?m)^\s+"""([^"]*)"""`)
)

func (f *FileSummarizer) summarizePython(content string) string {
	var sb strings.Builder
	sb.WriteString("# [SUMMARIZED - structural outline]\n\n")

	imports := pyImportRe.FindAllString(content, -1)
	if len(imports) > 0 {
		limit := min(len(imports), 15)
		for _, imp := range imports[:limit] {
			sb.WriteString(imp)
			sb.WriteString("\n")
		}
		if len(imports) > 15 {
			sb.WriteString(fmt.Sprintf("# ... and %d more imports\n", len(imports)-15))
		}
		sb.WriteString("\n")
	}

	classes := pyClassRe.FindAllString(content, -1)
	for _, cls := range classes {
		sb.WriteString(strings.TrimSpace(cls))
		sb.WriteString("\n    ...\n")
	}

	lines := strings.Split(content, "\n")
	for i, line := range lines {
		if pyFuncRe.MatchString(line) {
			sb.WriteString(strings.TrimSpace(line))
			sb.WriteString("\n")
			// Look for docstring on the next few lines
			for j := i + 1; j < len(lines) && j <= i+3; j++ {
				trimmed := strings.TrimSpace(lines[j])
				if strings.HasPrefix(trimmed, `"""`) {
					if strings.Count(trimmed, `"""`) >= 2 {
						sb.WriteString("    ")
						sb.WriteString(trimmed)
						sb.WriteString("\n")
					} else {
						// Multi-line docstring: collect until closing """
						sb.WriteString("    ")
						sb.WriteString(trimmed)
						sb.WriteString("\n")
						for k := j + 1; k < len(lines) && k <= j+5; k++ {
							dtrimmed := strings.TrimSpace(lines[k])
							sb.WriteString("    ")
							sb.WriteString(dtrimmed)
							sb.WriteString("\n")
							if strings.Contains(dtrimmed, `"""`) {
								break
							}
						}
					}
					break
				}
				if trimmed != "" {
					break
				}
			}
			sb.WriteString("    ...\n\n")
		}
	}

	return sb.String()
}

// --- Go ---

var (
	goPackageRe  = regexp.MustCompile(`(?m)^package\s+\w+`)
	goImportRe   = regexp.MustCompile(`(?ms)^import\s*\(([^)]*)\)`)
	goImportOneRe = regexp.MustCompile(`(?m)^import\s+"[^"]*"`)
	goTypeDeclRe = regexp.MustCompile(`(?m)^type\s+\w+\s+(?:struct|interface|func)\b[^{]*`)
	goTypeAliasRe = regexp.MustCompile(`(?m)^type\s+\w+\s+\w+`)
	goFuncRe     = regexp.MustCompile(`(?m)^func\s+(?:\([^)]+\)\s+)?\w+\s*\([^)]*\)(?:\s*(?:\([^)]*\)|[\w*\[\]]+))?`)
)

func (f *FileSummarizer) summarizeGo(content string) string {
	var sb strings.Builder
	sb.WriteString("// [SUMMARIZED - structural outline]\n\n")

	if m := goPackageRe.FindString(content); m != "" {
		sb.WriteString(m)
		sb.WriteString("\n\n")
	}

	if m := goImportRe.FindStringSubmatch(content); len(m) > 1 {
		sb.WriteString("import (\n")
		sb.WriteString(m[1])
		sb.WriteString(")\n\n")
	} else {
		singles := goImportOneRe.FindAllString(content, -1)
		for _, s := range singles {
			sb.WriteString(s)
			sb.WriteString("\n")
		}
		if len(singles) > 0 {
			sb.WriteString("\n")
		}
	}

	types := goTypeDeclRe.FindAllString(content, -1)
	for _, t := range types {
		sb.WriteString(strings.TrimSpace(t))
		sb.WriteString(" { ... }\n")
	}

	aliases := goTypeAliasRe.FindAllString(content, -1)
	for _, a := range aliases {
		trimmed := strings.TrimSpace(a)
		// Skip those already captured as struct/interface/func
		if strings.Contains(trimmed, "struct") || strings.Contains(trimmed, "interface") || strings.Contains(trimmed, "func") {
			continue
		}
		sb.WriteString(trimmed)
		sb.WriteString("\n")
	}

	funcs := goFuncRe.FindAllString(content, -1)
	if len(funcs) > 0 {
		sb.WriteString("\n// Functions\n")
		for _, fn := range funcs {
			sb.WriteString(strings.TrimSpace(fn))
			sb.WriteString(" { ... }\n")
		}
	}

	return sb.String()
}

// --- Unsupported ---

const maxTruncateChars = 2000

func (f *FileSummarizer) truncateUnsupported(content, ext string) string {
	notice := fmt.Sprintf("// [TRUNCATED - .%s files are not supported for structural summarization]\n// Showing first %d characters.\n\n", ext, maxTruncateChars)
	if len(content) <= maxTruncateChars {
		return notice + content
	}
	return notice + content[:maxTruncateChars] + "\n// ... truncated"
}
