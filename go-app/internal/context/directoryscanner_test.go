package context

import (
	"testing"

	"github.com/stretchr/testify/assert"
)

func TestDirectoryScanner_EmptyTree(t *testing.T) {
	scanner := NewDirectoryScanner()
	result := scanner.Scan([]string{})

	assert.NotNil(t, result)
	for _, cat := range []string{CategoryFrontend, CategoryBackend, CategoryInfrastructure, CategoryTest, CategoryConfig, CategoryDocs} {
		assert.Empty(t, result[cat], "category %s should be empty", cat)
	}
}

func TestDirectoryScanner_Frontend(t *testing.T) {
	scanner := NewDirectoryScanner()
	tree := []string{
		"ui/components/Button.tsx",
		"web/index.html",
		"static/logo.png",
		"public/favicon.ico",
		"assets/style.css",
		"views/home.ejs",
		"templates/layout.html",
		"js/app.js",
		"ts/utils.ts",
		"css/main.css",
	}
	result := scanner.Scan(tree)

	assert.NotEmpty(t, result[CategoryFrontend])
	dirs := result[CategoryFrontend]
	assertContainsDir(t, dirs, "ui")
	assertContainsDir(t, dirs, "web")
	assertContainsDir(t, dirs, "static")
	assertContainsDir(t, dirs, "public")
	assertContainsDir(t, dirs, "assets")
	assertContainsDir(t, dirs, "views")
	assertContainsDir(t, dirs, "templates")
	assertContainsDir(t, dirs, "js")
	assertContainsDir(t, dirs, "ts")
	assertContainsDir(t, dirs, "css")
}

func TestDirectoryScanner_Backend(t *testing.T) {
	scanner := NewDirectoryScanner()
	tree := []string{
		"application/build.gradle",
		"core/src/Main.java",
		"service/UserService.java",
		"controller/UserController.java",
		"model/User.java",
		"src/main/java/com/example/App.java",
		"app/server.py",
		"api/routes.go",
	}
	result := scanner.Scan(tree)

	assert.NotEmpty(t, result[CategoryBackend])
	dirs := result[CategoryBackend]
	assertContainsDir(t, dirs, "application")
	assertContainsDir(t, dirs, "core")
	assertContainsDir(t, dirs, "service")
	assertContainsDir(t, dirs, "controller")
	assertContainsDir(t, dirs, "model")
	assertContainsDir(t, dirs, "app")
	assertContainsDir(t, dirs, "api")
}

func TestDirectoryScanner_Infrastructure(t *testing.T) {
	scanner := NewDirectoryScanner()
	tree := []string{
		"infrastructure/main.tf",
		"infra/docker-compose.yml",
		"cdk/bin/app.ts",
		"terraform/modules/vpc/main.tf",
		"k8s/deployment.yaml",
		"docker/Dockerfile",
		"cloudformation/template.yaml",
	}
	result := scanner.Scan(tree)

	assert.NotEmpty(t, result[CategoryInfrastructure])
	dirs := result[CategoryInfrastructure]
	assertContainsDir(t, dirs, "infrastructure")
	assertContainsDir(t, dirs, "infra")
	assertContainsDir(t, dirs, "cdk")
	assertContainsDir(t, dirs, "terraform")
	assertContainsDir(t, dirs, "k8s")
	assertContainsDir(t, dirs, "docker")
	assertContainsDir(t, dirs, "cloudformation")
}

func TestDirectoryScanner_Test(t *testing.T) {
	scanner := NewDirectoryScanner()
	tree := []string{
		"test/unit/service_test.go",
		"tests/integration/api_test.py",
		"spec/models/user_spec.rb",
		"e2e/login.spec.ts",
		"it/smoke/health_it.java",
		"junit/UserServiceTest.java",
	}
	result := scanner.Scan(tree)

	assert.NotEmpty(t, result[CategoryTest])
	dirs := result[CategoryTest]
	assertContainsDir(t, dirs, "test")
	assertContainsDir(t, dirs, "tests")
	assertContainsDir(t, dirs, "spec")
	assertContainsDir(t, dirs, "e2e")
	assertContainsDir(t, dirs, "it")
	assertContainsDir(t, dirs, "junit")
}

func TestDirectoryScanner_Config(t *testing.T) {
	scanner := NewDirectoryScanner()
	tree := []string{
		"config/app.yaml",
		"configuration/settings.json",
		".github/workflows/ci.yml",
	}
	result := scanner.Scan(tree)

	assert.NotEmpty(t, result[CategoryConfig])
	dirs := result[CategoryConfig]
	assertContainsDir(t, dirs, "config")
	assertContainsDir(t, dirs, "configuration")
	assertContainsDir(t, dirs, ".github")
}

func TestDirectoryScanner_Docs(t *testing.T) {
	scanner := NewDirectoryScanner()
	tree := []string{
		"docs/api.md",
		"doc/guide.md",
		"documentation/index.html",
		"wiki/home.md",
	}
	result := scanner.Scan(tree)

	assert.NotEmpty(t, result[CategoryDocs])
	dirs := result[CategoryDocs]
	assertContainsDir(t, dirs, "docs")
	assertContainsDir(t, dirs, "doc")
	assertContainsDir(t, dirs, "documentation")
	assertContainsDir(t, dirs, "wiki")
}

func TestDirectoryScanner_MultiSegmentPattern(t *testing.T) {
	scanner := NewDirectoryScanner()
	tree := []string{
		"src/main/java/com/example/App.java",
	}
	result := scanner.Scan(tree)

	assert.NotEmpty(t, result[CategoryBackend])
}

func TestDirectoryScanner_NoDuplicates(t *testing.T) {
	scanner := NewDirectoryScanner()
	tree := []string{
		"test/unit/a_test.go",
		"test/unit/b_test.go",
		"test/integration/c_test.go",
	}
	result := scanner.Scan(tree)

	// "test" should appear only once
	count := 0
	for _, d := range result[CategoryTest] {
		if d == "test" {
			count++
		}
	}
	assert.Equal(t, 1, count, "test directory should appear exactly once")
}

func TestDirectoryScanner_MixedCategories(t *testing.T) {
	scanner := NewDirectoryScanner()
	tree := []string{
		"api/routes.go",
		"api/routes_test.go",
		"test/api_test.go",
		"docs/README.md",
		"config/app.yaml",
		"infrastructure/main.tf",
		"ui/index.html",
	}
	result := scanner.Scan(tree)

	assert.NotEmpty(t, result[CategoryBackend])
	assert.NotEmpty(t, result[CategoryTest])
	assert.NotEmpty(t, result[CategoryDocs])
	assert.NotEmpty(t, result[CategoryConfig])
	assert.NotEmpty(t, result[CategoryInfrastructure])
	assert.NotEmpty(t, result[CategoryFrontend])
}

func TestDirectoryScanner_EmptyPaths(t *testing.T) {
	scanner := NewDirectoryScanner()
	tree := []string{"", "", "test/unit.go"}
	result := scanner.Scan(tree)

	assert.NotEmpty(t, result[CategoryTest])
}

func TestDirectoryScanner_CaseInsensitive(t *testing.T) {
	scanner := NewDirectoryScanner()
	tree := []string{
		"Test/unit_test.go",
		"DOCS/readme.md",
		"UI/index.html",
	}
	result := scanner.Scan(tree)

	assert.NotEmpty(t, result[CategoryTest])
	assert.NotEmpty(t, result[CategoryDocs])
	assert.NotEmpty(t, result[CategoryFrontend])
}

func TestDirectoryScanner_BackslashNormalization(t *testing.T) {
	scanner := NewDirectoryScanner()
	tree := []string{
		"test\\unit\\service_test.go",
	}
	result := scanner.Scan(tree)

	assert.NotEmpty(t, result[CategoryTest])
}

func assertContainsDir(t *testing.T, dirs []string, expected string) {
	t.Helper()
	for _, d := range dirs {
		if d == expected {
			return
		}
	}
	t.Errorf("expected dirs %v to contain %q", dirs, expected)
}
