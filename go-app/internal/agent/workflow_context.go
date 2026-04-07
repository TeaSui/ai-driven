package agent

import (
	"fmt"
	"strings"
)

type WorkflowContext struct {
	PRUrl      string
	BranchName string
	Status     string
}

func (wc *WorkflowContext) ToPromptSection() string {
	var sb strings.Builder
	sb.WriteString("## Prior Automated Work\n")
	sb.WriteString("A Pull Request was automatically created for this ticket:\n")
	fmt.Fprintf(&sb, "- **PR URL:** %s\n", wc.PRUrl)
	if wc.BranchName != "" {
		fmt.Fprintf(&sb, "- **Branch:** %s\n", wc.BranchName)
	}
	fmt.Fprintf(&sb, "- **Status:** %s\n", wc.Status)
	sb.WriteString("\nYou can reference this PR and use tools to check its current state.\n\n")
	return sb.String()
}
