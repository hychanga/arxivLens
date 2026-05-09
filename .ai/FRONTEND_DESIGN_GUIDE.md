# Frontend Design Guidelines for Claude AI Assistant

## Design Philosophy
Create distinctive, production-grade interfaces with high design quality that avoid generic AI aesthetics. Focus on creative, polished code and UI design.

## When to Use This Guide
- Building web components, pages, or applications
- Creating React components, HTML/CSS layouts
- Styling or beautifying any web UI
- Developing websites, landing pages, or dashboards

---

## Core Principles

### 1. Avoid Generic AI Aesthetics
**DON'T:**
- Use gradient backgrounds everywhere
- Default to purple/blue color schemes
- Create overly rounded corners (border-radius > 12px)
- Use glassmorphism effects without purpose
- Add unnecessary shadows and blur effects

**DO:**
- Choose intentional, brand-appropriate colors
- Use subtle, purposeful visual effects
- Design with clear visual hierarchy
- Create clean, readable layouts

### 2. Typography Standards
- **Body text:** 14px-16px, line-height 1.5-1.6
- **Headings:** Clear hierarchy (H1: 2.5rem, H2: 2rem, H3: 1.5rem)
- **Font stacks:** System fonts for performance
  ```css
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 
               'Roboto', 'Oxygen', 'Ubuntu', sans-serif;
  ```

### 3. Color System
- Define a clear color palette (3-5 main colors)
- Use consistent naming: `primary`, `secondary`, `accent`, `neutral`
- Ensure WCAG AA contrast ratios (4.5:1 for text)
- Use CSS variables for theme management:
  ```css
  :root {
    --color-primary: #2563eb;
    --color-text: #1f2937;
    --color-bg: #ffffff;
  }
  ```

### 4. Spacing System
Use a consistent spacing scale (8px base):
- xs: 4px
- sm: 8px
- md: 16px
- lg: 24px
- xl: 32px
- 2xl: 48px

### 5. Component Design
- **Buttons:** Clear states (default, hover, active, disabled)
- **Forms:** Proper validation states and error messages
- **Cards:** Consistent padding and shadow
- **Navigation:** Clear active states

---

## React Best Practices

### Component Structure
```tsx
// Good: Functional component with TypeScript
interface ButtonProps {
  variant?: 'primary' | 'secondary';
  size?: 'sm' | 'md' | 'lg';
  children: React.ReactNode;
  onClick?: () => void;
}

export const Button: React.FC<ButtonProps> = ({ 
  variant = 'primary',
  size = 'md',
  children,
  onClick 
}) => {
  return (
    <button 
      className={`btn btn-${variant} btn-${size}`}
      onClick={onClick}
    >
      {children}
    </button>
  );
};
```

### State Management
- Use `useState` for local state
- Use `useContext` for shared state
- Consider `useReducer` for complex state logic
- Avoid prop drilling (max 2-3 levels)

### Performance
- Memoize expensive computations with `useMemo`
- Prevent unnecessary re-renders with `React.memo`
- Lazy load components with `React.lazy` + `Suspense`
- Optimize images (use WebP, lazy loading)

---

## CSS/Styling Approaches

### Tailwind CSS (Preferred for Rapid Development)
```tsx
// Use utility classes for quick styling
<div className="flex items-center gap-4 p-6 bg-white rounded-lg shadow-md">
  <img src="avatar.jpg" className="w-12 h-12 rounded-full" />
  <div>
    <h3 className="text-lg font-semibold text-gray-900">John Doe</h3>
    <p className="text-sm text-gray-600">Product Designer</p>
  </div>
</div>
```

**Tailwind Guidelines:**
- Group related utilities (layout → spacing → colors → typography)
- Extract repeated patterns into components
- Use `@apply` sparingly (only for base styles)

### CSS Modules (For Component Isolation)
```css
/* Button.module.css */
.button {
  padding: 0.5rem 1rem;
  border-radius: 0.375rem;
  font-weight: 500;
  transition: all 0.2s;
}

.button:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 6px rgba(0, 0, 0, 0.1);
}
```

### Styled Components (For Dynamic Styling)
```tsx
import styled from 'styled-components';

const Button = styled.button<{ variant: 'primary' | 'secondary' }>`
  padding: 0.5rem 1rem;
  background: ${props => props.variant === 'primary' ? '#2563eb' : '#6b7280'};
  color: white;
  border: none;
  border-radius: 0.375rem;
  
  &:hover {
    opacity: 0.9;
  }
`;
```

---

## Responsive Design

### Mobile-First Approach
```css
/* Mobile (default) */
.container {
  padding: 1rem;
}

/* Tablet */
@media (min-width: 768px) {
  .container {
    padding: 2rem;
  }
}

/* Desktop */
@media (min-width: 1024px) {
  .container {
    padding: 3rem;
    max-width: 1200px;
    margin: 0 auto;
  }
}
```

### Tailwind Breakpoints
```tsx
<div className="w-full md:w-1/2 lg:w-1/3">
  {/* Full width on mobile, half on tablet, third on desktop */}
</div>
```

---

## Accessibility (a11y)

### Essential Practices
1. **Semantic HTML:** Use proper tags (`<button>`, `<nav>`, `<main>`)
2. **ARIA labels:** Add when semantic HTML isn't enough
3. **Keyboard navigation:** Ensure all interactive elements are keyboard accessible
4. **Focus states:** Visible focus indicators
5. **Alt text:** Descriptive text for images

```tsx
// Good accessibility example
<button
  aria-label="Close dialog"
  onClick={handleClose}
  className="focus:outline-none focus:ring-2 focus:ring-blue-500"
>
  <CloseIcon aria-hidden="true" />
</button>

<img 
  src="product.jpg" 
  alt="Blue wireless headphones with noise cancellation"
/>
```

---

## File Structure

### Recommended Project Structure
```
frontend/
├── src/
│   ├── components/
│   │   ├── ui/              # Reusable UI components
│   │   │   ├── Button.tsx
│   │   │   ├── Input.tsx
│   │   │   └── Card.tsx
│   │   └── features/        # Feature-specific components
│   │       ├── auth/
│   │       └── dashboard/
│   ├── pages/               # Page components
│   ├── hooks/               # Custom React hooks
│   ├── utils/               # Utility functions
│   ├── styles/              # Global styles
│   │   ├── globals.css
│   │   └── variables.css
│   └── types/               # TypeScript types
├── public/                  # Static assets
└── package.json
```

---

## Common Patterns

### Loading States
```tsx
const DataDisplay = () => {
  const [data, setData] = useState(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState(null);

  if (loading) return <Spinner />;
  if (error) return <ErrorMessage message={error} />;
  if (!data) return <EmptyState />;
  
  return <DataTable data={data} />;
};
```

### Form Handling
```tsx
const LoginForm = () => {
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [errors, setErrors] = useState({});

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    // Validate
    // Submit
  };

  return (
    <form onSubmit={handleSubmit}>
      <Input
        type="email"
        value={email}
        onChange={(e) => setEmail(e.target.value)}
        error={errors.email}
        label="Email"
        required
      />
      {/* ... */}
    </form>
  );
};
```

### Modal/Dialog Pattern
```tsx
const Modal = ({ isOpen, onClose, children }) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center">
      <div className="bg-white rounded-lg p-6 max-w-md w-full">
        {children}
        <button onClick={onClose}>Close</button>
      </div>
    </div>
  );
};
```

---

## Performance Checklist

- [ ] Images optimized (WebP format, lazy loading)
- [ ] Code splitting implemented
- [ ] Unused dependencies removed
- [ ] CSS purged (remove unused styles)
- [ ] Bundle size analyzed
- [ ] Lighthouse score > 90

---

## Testing Considerations

```tsx
// Component should be testable
import { render, screen, fireEvent } from '@testing-library/react';

test('button triggers onClick', () => {
  const handleClick = jest.fn();
  render(<Button onClick={handleClick}>Click me</Button>);
  
  fireEvent.click(screen.getByText('Click me'));
  expect(handleClick).toHaveBeenCalledTimes(1);
});
```

---

## Before Shipping

1. **Cross-browser testing:** Chrome, Firefox, Safari, Edge
2. **Mobile testing:** iOS Safari, Android Chrome
3. **Accessibility audit:** Use axe DevTools
4. **Performance audit:** Lighthouse CI
5. **Error tracking:** Sentry or similar
6. **Analytics:** Google Analytics or Plausible

---

## Quick Reference: Common Mistakes to Avoid

❌ **Don't:**
- Use inline styles everywhere
- Forget mobile responsiveness
- Ignore loading/error states
- Skip accessibility attributes
- Use non-semantic HTML (div soup)
- Have inconsistent spacing/colors
- Forget to optimize images
- Ignore TypeScript errors

✅ **Do:**
- Use consistent design tokens
- Think mobile-first
- Handle all UI states (loading, error, empty, success)
- Add proper ARIA labels
- Use semantic HTML elements
- Follow a spacing system
- Optimize assets before deployment
- Maintain strict TypeScript checking

---

## Resources
- [Tailwind CSS Docs](https://tailwindcss.com/docs)
- [React Docs](https://react.dev)
- [WebAIM (Accessibility)](https://webaim.org/)
- [CSS Tricks](https://css-tricks.com/)
- [Frontend Checklist](https://frontendchecklist.io/)
