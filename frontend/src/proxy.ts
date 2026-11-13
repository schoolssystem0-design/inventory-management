import { NextResponse, type NextRequest } from "next/server";
import { decideRoute } from "@/lib/routing";

/** Routes each request by host: platform portal, a shop's app, or the landing page (src/lib/routing.ts). */
export function proxy(request: NextRequest) {
  const decision = decideRoute(request.headers.get("host") ?? "", request.nextUrl.pathname);
  switch (decision.kind) {
    case "rewrite": {
      const url = request.nextUrl.clone();
      url.pathname = decision.pathname;
      return NextResponse.rewrite(url);
    }
    case "notFound":
      return new NextResponse("Not found", { status: 404 });
    default:
      return NextResponse.next();
  }
}

export const config = {
  matcher: ["/((?!_next/static|_next/image|favicon.ico|api/|manifest.webmanifest|icons/|.*\\.[a-zA-Z0-9]+$).*)"],
};
