import unittest

from fastapi.routing import APIRoute

from main import app
from route.dependencies import get_current_user


class BillableEndpointAuthenticationTests(unittest.TestCase):
    def test_billable_endpoints_require_user_bearer_authentication(self):
        schema = app.openapi()
        billable_operations = (
            ("/route/generate-path", "post"),
            ("/route/generate-script/park", "post"),
            ("/route/generate-script/without-park", "post"),
            ("/tts", "post"),
        )

        for path, method in billable_operations:
            with self.subTest(path=path):
                security = schema["paths"][path][method].get("security", [])
                self.assertIn({"HTTPBearer": []}, security)

                route = next(
                    route
                    for route in app.routes
                    if isinstance(route, APIRoute)
                    and route.path == path
                    and method.upper() in route.methods
                )
                dependency_calls = {
                    dependency.call for dependency in route.dependant.dependencies
                }
                self.assertIn(get_current_user, dependency_calls)


if __name__ == "__main__":
    unittest.main()
