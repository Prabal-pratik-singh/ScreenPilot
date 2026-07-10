/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,jsx}'],
  theme: {
    extend: {
      colors: {
        marigold: {
          DEFAULT: '#F6A821',
          50: '#FEF6E7',
          100: '#FDEBC7',
          200: '#FBD88E',
          300: '#F9C456',
          400: '#F7B63B',
          500: '#F6A821',
          600: '#D88C09',
          700: '#A96D07',
          800: '#7A4F05',
          900: '#4B3003',
        },
        ink: {
          DEFAULT: '#16233F',
          50: '#EEF1F7',
          100: '#D5DCEA',
          200: '#A9B7D2',
          300: '#7E92BA',
          400: '#56709F',
          500: '#3D5378',
          600: '#2C3E5C',
          700: '#212F48',
          800: '#16233F',
          900: '#0D1526',
        },
        cream: '#FAF8F4',
        success: {
          DEFAULT: '#22C55E',
          100: '#DCFCE7',
          700: '#15803D',
        },
        danger: {
          DEFAULT: '#EF4444',
          100: '#FEE2E2',
          700: '#B91C1C',
        },
        warning: {
          DEFAULT: '#F59E0B',
          100: '#FEF3C7',
          700: '#B45309',
        },
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
      },
      boxShadow: {
        card: '0 1px 3px rgba(22, 35, 63, 0.06), 0 4px 14px rgba(22, 35, 63, 0.05)',
      },
    },
  },
  plugins: [],
}
