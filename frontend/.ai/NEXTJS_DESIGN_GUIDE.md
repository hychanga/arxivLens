# Next.js Frontend Design Guidelines for Claude AI Assistant

## Project Context
This is a **Next.js 15/16 project** using the **App Router** (not Pages Router).

**Current Version: Next.js 16.2** (March 2026)
- React 19.2 support
- Turbopack stable (default bundler)
- Cache Components with `use cache` directive
- No default caching (opt-in model)

---

## 🚨 Next.js 15/16 Breaking Changes

### 1. Async Request APIs (Next.js 15+)
**All request APIs are now async and must be awaited:**

```tsx
// ❌ OLD (Next.js 14)
import { cookies, headers } from 'next/headers';

export default function Page() {
  const cookieStore = cookies();
  const headersList = headers();
}

// ✅ NEW (Next.js 15+)
import { cookies, headers } from 'next/headers';

export default async function Page() {
  const cookieStore = await cookies();
  const headersList = await headers();
}
```

**Affected APIs:**
- `cookies()`
- `headers()`
- `draftMode()`
- `params` in layouts/pages
- `searchParams` in pages

### 2. No Default Caching (Next.js 15+)
**Nothing is cached by default anymore:**

```tsx
// ❌ OLD: This was cached by default
fetch('https://api.example.com/data')

// ✅ NEW: Must explicitly opt-in to caching
fetch('https://api.example.com/data', {
  next: { revalidate: 3600 } // Cache for 1 hour
})

// Or use the new 'use cache' directive (Next.js 16)
'use cache';
export async function getData() {
  return fetch('https://api.example.com/data').then(r => r.json());
}
```

**What changed:**
- `fetch()` requests: **not cached** by default
- GET Route Handlers: **not cached** by default  
- Client-side router cache: **not cached** by default

**To opt-in to caching:**
```tsx
// Option 1: fetch with revalidate
fetch(url, { next: { revalidate: 3600 } })

// Option 2: Route segment config
export const revalidate = 3600; // seconds

// Option 3: 'use cache' directive (Next.js 16)
'use cache';
export async function getProducts() {
  return db.products.findMany();
}
```

### 3. React 19 Required (Next.js 15+)
```bash
# Minimum versions
npm install next@latest react@19 react-dom@19
```

**New React 19 features:**
- `useActionState` (replaces `useFormState`)
- `useOptimistic`
- `use()` hook for unwrapping promises
- React Compiler (automatic memoization)

### 4. Turbopack is Default (Next.js 16+)
```bash
# Turbopack is now the default bundler for development
next dev  # Uses Turbopack automatically

# Production builds still use Webpack (Turbopack prod coming later in 2026)
next build
```

**Benefits:**
- 2-5× faster builds
- 5-10× faster Fast Refresh
- File system caching for even faster restarts

---

## Next.js Core Principles

### 1. App Router vs Pages Router
**WE USE APP ROUTER** (Next.js 13+)
```
app/
├── layout.tsx          # Root layout
├── page.tsx            # Home page
├── about/
│   └── page.tsx        # /about route
└── blog/
    ├── page.tsx        # /blog route
    └── [slug]/
        └── page.tsx    # /blog/[slug] dynamic route
```

**DON'T use Pages Router patterns:**
❌ `pages/index.tsx`
❌ `pages/api/hello.ts`
✅ `app/page.tsx`
✅ `app/api/hello/route.ts`

### 2. Server Components by Default
**Every component is a Server Component unless you add `'use client'`**

```tsx
// ✅ Server Component (default) - Can fetch data directly
export default async function BlogPost({ params }: { params: { slug: string } }) {
  const post = await fetch(`/api/posts/${params.slug}`).then(r => r.json());
  
  return <article>{post.content}</article>;
}

// ✅ Client Component - Only when needed for interactivity
'use client';

import { useState } from 'react';

export default function Counter() {
  const [count, setCount] = useState(0);
  return <button onClick={() => setCount(count + 1)}>{count}</button>;
}
```

**When to use `'use client'`:**
- useState, useEffect, useContext
- Event handlers (onClick, onChange, etc.)
- Browser APIs (localStorage, window, etc.)
- Third-party libraries that use hooks

**When to keep Server Component:**
- Data fetching
- Direct database access
- Accessing backend resources
- SEO-critical content
- Large dependencies (won't be sent to client)

### 3. File Conventions

| File | Purpose | Example |
|------|---------|---------|
| `layout.tsx` | Shared UI for a route segment | Navigation, footer |
| `page.tsx` | Unique UI for a route | Main content |
| `loading.tsx` | Loading UI (Suspense fallback) | Skeleton screens |
| `error.tsx` | Error UI boundary | Error message |
| `not-found.tsx` | 404 UI | Custom 404 page |
| `route.ts` | API endpoint | REST API handler |

---

## Data Fetching Patterns

### Server-Side Fetching (Preferred)
```tsx
// app/products/page.tsx
export default async function ProductsPage() {
  // Next.js 15+: NOT cached by default
  const products = await fetch('https://api.example.com/products', {
    next: { revalidate: 3600 } // Explicitly cache for 1 hour
  }).then(r => r.json());

  return (
    <div>
      {products.map(product => (
        <ProductCard key={product.id} {...product} />
      ))}
    </div>
  );
}
```

### NEW: use cache Directive (Next.js 16+)
**Cache entire functions with explicit cache control:**

```tsx
// app/lib/data.ts
'use cache';

export async function getProducts() {
  const products = await db.products.findMany();
  return products;
}

// Optional: Configure cache behavior
export async function getProductById(id: string) {
  'use cache';
  cacheLife('hours'); // Built-in cache lifetime presets
  
  return db.products.findUnique({ where: { id } });
}

// Use in your page
import { getProducts } from '@/lib/data';

export default async function ProductsPage() {
  const products = await getProducts(); // Cached automatically
  return <div>{/* ... */}</div>;
}
```

**cacheLife presets:**
- `'seconds'` - 60 seconds
- `'minutes'` - 5 minutes  
- `'hours'` - 1 hour
- `'days'` - 1 day
- `'weeks'` - 1 week
- `'max'` - 1 year

**Or use custom cacheLife:**
```tsx
'use cache';
cacheLife({ revalidate: 900 }); // 15 minutes
```

### Cache Tags for Revalidation
```tsx
'use cache';
cacheTag('products');

export async function getProducts() {
  return db.products.findMany();
}

// Revalidate from a Server Action
'use server';
import { revalidateTag } from 'next/cache';

export async function createProduct(data: FormData) {
  await db.products.create({ data });
  revalidateTag('products'); // Invalidates cache
}
```

### Client-Side Fetching (When Needed)
```tsx
// app/components/SearchResults.tsx
'use client';

import { useState, useEffect } from 'react';

export default function SearchResults({ query }: { query: string }) {
  const [results, setResults] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch(`/api/search?q=${query}`)
      .then(r => r.json())
      .then(data => {
        setResults(data);
        setLoading(false);
      });
  }, [query]);

  if (loading) return <Spinner />;
  return <ResultsList results={results} />;
}
```

### Streaming with Suspense
```tsx
// app/dashboard/page.tsx
import { Suspense } from 'react';

export default function Dashboard() {
  return (
    <div>
      <h1>Dashboard</h1>
      
      {/* This part loads immediately */}
      <QuickStats />
      
      {/* This part streams in when ready */}
      <Suspense fallback={<ChartSkeleton />}>
        <RevenueChart />
      </Suspense>
      
      <Suspense fallback={<TableSkeleton />}>
        <RecentOrders />
      </Suspense>
    </div>
  );
}

// Separate async component
async function RevenueChart() {
  const data = await fetchChartData(); // This is async!
  return <Chart data={data} />;
}
```

---

## Routing & Navigation

### Link Component
```tsx
import Link from 'next/link';

// ✅ Good
<Link href="/about">About</Link>
<Link href={`/blog/${post.slug}`}>Read more</Link>

// ❌ Don't use <a> for internal links
<a href="/about">About</a>
```

### Programmatic Navigation
```tsx
'use client';

import { useRouter } from 'next/navigation'; // NOT 'next/router'!

export default function LoginForm() {
  const router = useRouter();

  const handleSubmit = async (e) => {
    e.preventDefault();
    // ... login logic
    router.push('/dashboard');
  };

  return <form onSubmit={handleSubmit}>...</form>;
}
```

### Dynamic Routes
```tsx
// app/blog/[slug]/page.tsx
// IMPORTANT: params is now a Promise in Next.js 15+
export default async function BlogPost({ 
  params 
}: { 
  params: Promise<{ slug: string }> 
}) {
  const { slug } = await params; // Must await
  return <h1>Post: {slug}</h1>;
}

// Generate static pages at build time
export async function generateStaticParams() {
  const posts = await fetch('https://api.example.com/posts').then(r => r.json());
  
  return posts.map((post) => ({
    slug: post.slug,
  }));
}

// Dynamic metadata also needs to await params
export async function generateMetadata({ 
  params 
}: { 
  params: Promise<{ slug: string }> 
}) {
  const { slug } = await params;
  const post = await getPost(slug);
  
  return {
    title: post.title,
  };
}
```

### Route Groups (Organization)
```
app/
├── (marketing)/       # Doesn't affect URL
│   ├── layout.tsx     # Marketing-specific layout
│   ├── page.tsx       # / route
│   └── about/
│       └── page.tsx   # /about route
└── (dashboard)/
    ├── layout.tsx     # Dashboard layout
    └── page.tsx       # /dashboard route (wait, this needs to be in dashboard/ folder!)
```

Actually, correct structure:
```
app/
├── (marketing)/
│   ├── layout.tsx
│   ├── page.tsx       # /
│   └── about/
│       └── page.tsx   # /about
└── dashboard/
    ├── layout.tsx
    └── page.tsx       # /dashboard
```

---

## Styling with Tailwind CSS

### Installation (if not already done)
```bash
npx create-next-app@latest --tailwind
```

### Component Patterns
```tsx
// app/components/Button.tsx
import { ButtonHTMLAttributes } from 'react';

interface ButtonProps extends ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: 'primary' | 'secondary' | 'outline';
  size?: 'sm' | 'md' | 'lg';
}

export function Button({ 
  variant = 'primary', 
  size = 'md', 
  className = '',
  children,
  ...props 
}: ButtonProps) {
  const baseStyles = 'font-semibold rounded-lg transition-colors';
  
  const variants = {
    primary: 'bg-blue-600 text-white hover:bg-blue-700',
    secondary: 'bg-gray-200 text-gray-900 hover:bg-gray-300',
    outline: 'border-2 border-blue-600 text-blue-600 hover:bg-blue-50'
  };
  
  const sizes = {
    sm: 'px-3 py-1.5 text-sm',
    md: 'px-4 py-2 text-base',
    lg: 'px-6 py-3 text-lg'
  };
  
  return (
    <button 
      className={`${baseStyles} ${variants[variant]} ${sizes[size]} ${className}`}
      {...props}
    >
      {children}
    </button>
  );
}
```

### Global Styles
```css
/* app/globals.css */
@tailwind base;
@tailwind components;
@tailwind utilities;

@layer base {
  :root {
    --color-primary: 220 90% 56%;
    --color-background: 0 0% 100%;
    --color-foreground: 222.2 84% 4.9%;
  }

  .dark {
    --color-background: 222.2 84% 4.9%;
    --color-foreground: 210 40% 98%;
  }
}

@layer components {
  .btn-primary {
    @apply px-4 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700;
  }
}
```

---

## Image Optimization

### Next.js Image Component
```tsx
import Image from 'next/image';

// ✅ Local images
import heroImage from '@/public/hero.jpg';

<Image
  src={heroImage}
  alt="Hero banner"
  placeholder="blur" // Automatic blur placeholder
  priority // Load immediately (above the fold)
/>

// ✅ Remote images
<Image
  src="https://example.com/photo.jpg"
  alt="Product photo"
  width={500}
  height={300}
  className="rounded-lg"
/>

// ✅ Fill container (responsive)
<div className="relative w-full h-64">
  <Image
    src="/banner.jpg"
    alt="Banner"
    fill
    className="object-cover"
  />
</div>
```

### Image Configuration
```js
// next.config.js
module.exports = {
  images: {
    remotePatterns: [
      {
        protocol: 'https',
        hostname: 'example.com',
      },
    ],
  },
};
```

---

## SEO & Metadata

### Static Metadata
```tsx
// app/page.tsx
import { Metadata } from 'next';

export const metadata: Metadata = {
  title: 'Home - My Next.js App',
  description: 'Welcome to my awesome Next.js application',
  openGraph: {
    title: 'Home - My Next.js App',
    description: 'Welcome to my awesome Next.js application',
    images: ['/og-image.jpg'],
  },
};

export default function Home() {
  return <h1>Welcome</h1>;
}
```

### Dynamic Metadata
```tsx
// app/blog/[slug]/page.tsx
export async function generateMetadata({ params }): Promise<Metadata> {
  const post = await fetch(`/api/posts/${params.slug}`).then(r => r.json());
  
  return {
    title: post.title,
    description: post.excerpt,
    openGraph: {
      title: post.title,
      description: post.excerpt,
      images: [post.coverImage],
    },
  };
}
```

---

## API Routes

### REST API Endpoint
```ts
// app/api/posts/route.ts
import { NextRequest, NextResponse } from 'next/server';

export async function GET(request: NextRequest) {
  const searchParams = request.nextUrl.searchParams;
  const page = searchParams.get('page') || '1';
  
  const posts = await fetchPosts(parseInt(page));
  
  return NextResponse.json({ posts });
}

export async function POST(request: NextRequest) {
  const body = await request.json();
  
  // Validate & create post
  const newPost = await createPost(body);
  
  return NextResponse.json({ post: newPost }, { status: 201 });
}
```

### Dynamic API Route
```ts
// app/api/posts/[id]/route.ts
export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ id: string }> } // CHANGED in Next.js 15+
) {
  const { id } = await params; // Must await params
  const post = await fetchPost(id);
  
  if (!post) {
    return NextResponse.json({ error: 'Not found' }, { status: 404 });
  }
  
  return NextResponse.json({ post });
}
```

### Server Actions (Preferred for Mutations)
**Server Actions are now the recommended way to handle form submissions and mutations:**

```tsx
// app/actions.ts
'use server';

import { revalidatePath } from 'next/cache';

export async function createPost(formData: FormData) {
  const title = formData.get('title') as string;
  const content = formData.get('content') as string;
  
  // Validate
  if (!title || !content) {
    return { error: 'Title and content are required' };
  }
  
  // Create post
  const post = await db.posts.create({
    data: { title, content }
  });
  
  // Revalidate the posts page
  revalidatePath('/posts');
  
  return { success: true, post };
}

// Use in a form
// app/posts/new/page.tsx
import { createPost } from '@/app/actions';

export default function NewPostPage() {
  return (
    <form action={createPost}>
      <input name="title" required />
      <textarea name="content" required />
      <button type="submit">Create Post</button>
    </form>
  );
}
```

### useActionState for Client-Side State
```tsx
'use client';

import { useActionState } from 'react'; // React 19
import { createPost } from '@/app/actions';

export default function PostForm() {
  const [state, formAction, pending] = useActionState(createPost, null);
  
  return (
    <form action={formAction}>
      <input name="title" required />
      <textarea name="content" required />
      <button type="submit" disabled={pending}>
        {pending ? 'Creating...' : 'Create Post'}
      </button>
      {state?.error && <p className="text-red-500">{state.error}</p>}
      {state?.success && <p className="text-green-500">Post created!</p>}
    </form>
  );
}
```

---

## Partial Prerendering (PPR) - Next.js 16

**PPR allows mixing static and dynamic content in the same route.**  
The static shell loads instantly from CDN while dynamic parts stream in.

### Enable PPR
```js
// next.config.js
module.exports = {
  experimental: {
    ppr: true, // Still experimental in 16.2, will be stable soon
  },
};
```

### How PPR Works
```tsx
// app/product/[id]/page.tsx
import { Suspense } from 'react';

export default async function ProductPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  
  return (
    <div>
      {/* Static shell - prerendered at build time */}
      <Header />
      <ProductImages productId={id} />
      
      {/* Dynamic content - streamed in */}
      <Suspense fallback={<PriceSkeleton />}>
        <ProductPrice productId={id} />
      </Suspense>
      
      <Suspense fallback={<ReviewsSkeleton />}>
        <ProductReviews productId={id} />
      </Suspense>
      
      {/* Static footer */}
      <Footer />
    </div>
  );
}

// This component is dynamic (fetches live data)
async function ProductPrice({ productId }: { productId: string }) {
  const price = await fetch(`/api/prices/${productId}`, {
    cache: 'no-store' // Always fresh
  }).then(r => r.json());
  
  return <div className="text-2xl font-bold">${price}</div>;
}
```

**PPR Benefits:**
- ✅ Instant page load (static shell from CDN)
- ✅ Fresh dynamic data (streamed in)
- ✅ No layout shift (Suspense boundaries define space)
- ✅ Best of static + dynamic

**When to use PPR:**
- Product pages (static layout + dynamic pricing/inventory)
- Blog posts (static content + dynamic comments)
- Dashboard (static nav/sidebar + dynamic data)

---

## Loading States & Error Handling

### Loading UI
```tsx
// app/dashboard/loading.tsx
export default function Loading() {
  return (
    <div className="animate-pulse">
      <div className="h-8 bg-gray-200 rounded w-1/4 mb-4"></div>
      <div className="h-64 bg-gray-200 rounded"></div>
    </div>
  );
}
```

### Error Boundary
```tsx
// app/dashboard/error.tsx
'use client';

export default function Error({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <div className="p-8 text-center">
      <h2 className="text-2xl font-bold mb-4">Something went wrong!</h2>
      <p className="text-gray-600 mb-4">{error.message}</p>
      <button
        onClick={reset}
        className="px-4 py-2 bg-blue-600 text-white rounded"
      >
        Try again
      </button>
    </div>
  );
}
```

### Not Found
```tsx
// app/blog/[slug]/not-found.tsx
export default function NotFound() {
  return (
    <div className="text-center py-16">
      <h2 className="text-3xl font-bold mb-4">Post Not Found</h2>
      <p className="text-gray-600">The blog post you're looking for doesn't exist.</p>
    </div>
  );
}

// In page.tsx, trigger it:
import { notFound } from 'next/navigation';

export default async function BlogPost({ params }) {
  const post = await fetchPost(params.slug);
  
  if (!post) {
    notFound(); // Triggers not-found.tsx
  }
  
  return <article>{post.content}</article>;
}
```

---

## File & Folder Structure

### Recommended Structure
```
my-nextjs-app/
├── app/
│   ├── (auth)/                 # Route group
│   │   ├── login/
│   │   │   └── page.tsx
│   │   └── register/
│   │       └── page.tsx
│   ├── (dashboard)/
│   │   ├── layout.tsx
│   │   ├── dashboard/
│   │   │   ├── page.tsx
│   │   │   ├── loading.tsx
│   │   │   └── error.tsx
│   │   └── settings/
│   │       └── page.tsx
│   ├── api/
│   │   ├── auth/
│   │   │   └── [...nextauth]/
│   │   │       └── route.ts
│   │   └── posts/
│   │       ├── route.ts
│   │       └── [id]/
│   │           └── route.ts
│   ├── components/            # Shared components
│   │   ├── ui/                # Reusable UI components
│   │   │   ├── Button.tsx
│   │   │   ├── Input.tsx
│   │   │   └── Card.tsx
│   │   └── features/          # Feature-specific components
│   │       ├── Header.tsx
│   │       └── Footer.tsx
│   ├── layout.tsx             # Root layout
│   ├── page.tsx               # Home page
│   ├── globals.css
│   └── not-found.tsx
├── lib/                       # Utility functions
│   ├── db.ts
│   ├── auth.ts
│   └── utils.ts
├── types/                     # TypeScript types
│   └── index.ts
├── public/                    # Static assets
│   ├── images/
│   └── fonts/
├── next.config.js
├── tailwind.config.ts
└── package.json
```

---

## Performance Best Practices

### 1. Use Server Components by Default
```tsx
// ✅ Good - Server Component
export default async function ProductList() {
  const products = await db.products.findMany();
  return <div>{products.map(p => <ProductCard {...p} />)}</div>;
}

// ❌ Bad - Unnecessary Client Component
'use client';
export default function ProductList() {
  const [products, setProducts] = useState([]);
  useEffect(() => {
    fetch('/api/products').then(r => r.json()).then(setProducts);
  }, []);
  return <div>{products.map(p => <ProductCard {...p} />)}</div>;
}
```

### 2. Lazy Load Client Components
```tsx
import dynamic from 'next/dynamic';

const HeavyChart = dynamic(() => import('@/components/HeavyChart'), {
  loading: () => <ChartSkeleton />,
  ssr: false, // Don't render on server
});

export default function Dashboard() {
  return (
    <div>
      <h1>Dashboard</h1>
      <HeavyChart />
    </div>
  );
}
```

### 3. Optimize Fonts
```tsx
// app/layout.tsx
import { Inter, Roboto_Mono } from 'next/font/google';

const inter = Inter({
  subsets: ['latin'],
  display: 'swap',
  variable: '--font-inter',
});

const robotoMono = Roboto_Mono({
  subsets: ['latin'],
  display: 'swap',
  variable: '--font-roboto-mono',
});

export default function RootLayout({ children }) {
  return (
    <html lang="en" className={`${inter.variable} ${robotoMono.variable}`}>
      <body className="font-sans">{children}</body>
    </html>
  );
}
```

### 4. Caching Strategies
```tsx
// Revalidate every 1 hour
fetch('https://api.example.com/data', {
  next: { revalidate: 3600 }
});

// Cache forever (until manual revalidation)
fetch('https://api.example.com/static-data', {
  cache: 'force-cache'
});

// Never cache
fetch('https://api.example.com/live-data', {
  cache: 'no-store'
});
```

---

## TypeScript Best Practices

### Page Props
```tsx
// app/blog/[slug]/page.tsx
interface PageProps {
  params: { slug: string };
  searchParams: { [key: string]: string | string[] | undefined };
}

export default function BlogPost({ params, searchParams }: PageProps) {
  return <div>Post: {params.slug}</div>;
}
```

### Component Props
```tsx
// app/components/ProductCard.tsx
interface ProductCardProps {
  id: string;
  name: string;
  price: number;
  image?: string;
  onAddToCart?: (id: string) => void;
}

export function ProductCard({ id, name, price, image, onAddToCart }: ProductCardProps) {
  return (
    <div className="border rounded-lg p-4">
      <h3>{name}</h3>
      <p>${price}</p>
      {onAddToCart && (
        <button onClick={() => onAddToCart(id)}>Add to Cart</button>
      )}
    </div>
  );
}
```

---

## Environment Variables

### Setup
```env
# .env.local (gitignored)
DATABASE_URL="postgresql://..."
NEXT_PUBLIC_API_URL="https://api.example.com"
SECRET_KEY="your-secret-key"
```

### Usage
```tsx
// Server Component or API Route
const dbUrl = process.env.DATABASE_URL;

// Client Component (must have NEXT_PUBLIC_ prefix)
const apiUrl = process.env.NEXT_PUBLIC_API_URL;
```

---

## Common Patterns

### Layout with Navigation
```tsx
// app/layout.tsx
import Link from 'next/link';
import './globals.css';

export default function RootLayout({ children }) {
  return (
    <html lang="en">
      <body>
        <nav className="border-b">
          <div className="container mx-auto px-4 py-4">
            <ul className="flex gap-6">
              <li><Link href="/">Home</Link></li>
              <li><Link href="/about">About</Link></li>
              <li><Link href="/blog">Blog</Link></li>
            </ul>
          </div>
        </nav>
        <main className="container mx-auto px-4 py-8">
          {children}
        </main>
        <footer className="border-t mt-16 py-8">
          <p className="text-center text-gray-600">© 2026 My App</p>
        </footer>
      </body>
    </html>
  );
}
```

### Protected Route
```tsx
// app/dashboard/layout.tsx
import { redirect } from 'next/navigation';
import { getServerSession } from 'next-auth';

export default async function DashboardLayout({ children }) {
  const session = await getServerSession();
  
  if (!session) {
    redirect('/login');
  }
  
  return (
    <div className="flex">
      <aside className="w-64 border-r">
        {/* Sidebar */}
      </aside>
      <div className="flex-1">{children}</div>
    </div>
  );
}
```

### Form with Server Actions
```tsx
// app/contact/page.tsx
import { revalidatePath } from 'next/cache';

async function submitForm(formData: FormData) {
  'use server';
  
  const name = formData.get('name');
  const email = formData.get('email');
  
  // Save to database
  await db.contacts.create({ data: { name, email } });
  
  revalidatePath('/contact');
}

export default function ContactPage() {
  return (
    <form action={submitForm}>
      <input type="text" name="name" required />
      <input type="email" name="email" required />
      <button type="submit">Submit</button>
    </form>
  );
}
```

---

## Deployment Checklist

- [ ] Remove console.logs
- [ ] Set up proper environment variables
- [ ] Configure `next.config.js` (image domains, etc.)
- [ ] Add `robots.txt` and `sitemap.xml`
- [ ] Test production build locally: `npm run build && npm start`
- [ ] Check Lighthouse scores
- [ ] Set up error tracking (Sentry)
- [ ] Configure analytics
- [ ] Test on multiple devices/browsers

---

## Quick Reference

### Don't Forget (Next.js 15/16)
- ✅ **Await all request APIs** (`cookies()`, `headers()`, `params`)
- ✅ **Caching is opt-in** (use `next: { revalidate }` or `use cache`)
- ✅ Server Components by default
- ✅ `'use client'` only when needed
- ✅ Use `next/link` for navigation
- ✅ Use `next/image` for images
- ✅ Use Server Actions for mutations (not API routes)
- ✅ Metadata for SEO
- ✅ loading.tsx and error.tsx
- ✅ Type safety with TypeScript
- ✅ Tailwind for styling
- ✅ React 19 required

### Common Imports (Updated for Next.js 15/16)
```tsx
// Navigation
import Link from 'next/link';
import { useRouter, usePathname, useSearchParams } from 'next/navigation';

// Images
import Image from 'next/image';

// Metadata
import { Metadata } from 'next';

// Server utilities
import { notFound, redirect } from 'next/navigation';
import { revalidatePath, revalidateTag } from 'next/cache';

// API
import { NextRequest, NextResponse } from 'next/server';

// React 19 Hooks
import { useActionState, useOptimistic } from 'react';

// Request APIs (must await!)
import { cookies, headers, draftMode } from 'next/headers';
```

### Migration Checklist (14 → 15/16)
- [ ] Update to React 19: `npm install react@19 react-dom@19`
- [ ] Update Next.js: `npm install next@latest`
- [ ] Await all `cookies()`, `headers()`, `params` calls
- [ ] Add explicit caching where needed (`next: { revalidate }`)
- [ ] Replace `useFormState` with `useActionState`
- [ ] Test all dynamic routes (params is now async)
- [ ] Consider enabling PPR for hybrid pages
- [ ] Update TypeScript types for async params

---

## Resources
- [Next.js 16 Docs](https://nextjs.org/docs)
- [Next.js 16 Upgrade Guide](https://nextjs.org/docs/app/guides/upgrading/version-16)
- [Next.js 15 Upgrade Guide](https://nextjs.org/docs/app/guides/upgrading/version-15)
- [React 19 Docs](https://react.dev/blog/2024/12/05/react-19)
- [Next.js Examples](https://github.com/vercel/next.js/tree/canary/examples)
- [Tailwind CSS](https://tailwindcss.com/docs)
- [TypeScript Handbook](https://www.typescriptlang.org/docs/)
- [Turbopack Docs](https://nextjs.org/docs/architecture/turbopack)

---

## What's New Summary

### Next.js 16 (October 2025)
- ✨ **Turbopack stable** - 2-5× faster builds by default
- ✨ **Cache Components** - `use cache` directive for explicit caching
- ✨ **PPR stable** - Mix static and dynamic in one route
- ✨ **React 19.2** - View Transitions, useEffectEvent
- ✨ **Faster routing** - Layout deduplication, incremental prefetching

### Next.js 15 (October 2024)
- ⚠️ **Async request APIs** - Must await cookies(), headers(), params
- ⚠️ **No default caching** - Opt-in with `next: { revalidate }`
- ✨ **React 19 support** - useActionState, Server Actions stable
- ✨ **Turbopack dev** - 5-10× faster Fast Refresh

### Key Takeaway
**Next.js 16 is production-ready.** The breaking changes from 15 are manageable with codemods. If starting new, use 16. If migrating, budget 4-8 hours for testing.
