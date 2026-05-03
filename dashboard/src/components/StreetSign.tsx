import React from 'react';

interface StreetSignProps {
  type: 'HOR' | 'CYC' | 'RUN' | 'PED' | 'GRP' | string;
  size?: number;
}

const StreetSign: React.FC<StreetSignProps> = ({ type, size = 48 }) => {
  // Helper to get the pictogram based on type
  const getPictogram = () => {
    // The images have a white background, so we use mix-blend-mode: multiply 
    // to make the white background transparent and only show the dark silhouette
    // inside our red warning triangle SVG.
    const imageStyle = { mixBlendMode: 'multiply' as any };

    switch (type) {
      case 'HOR':
      case 'HOS':
        return <image href="/icons/horse.png" x="12" y="15" height="24" width="24" style={imageStyle} />;
      case 'CYC':
      case 'CYS':
        return <image href="/icons/bike.png" x="12" y="15" height="24" width="24" style={imageStyle} />;
      case 'RUN':
      case 'RUS':
        return <image href="/icons/runner.png" x="12" y="15" height="24" width="24" style={imageStyle} />;
      case 'PED':
      case 'WAL':
      case 'WAS':
      default:
        return <image href="/icons/walker.png" x="12" y="15" height="24" width="24" style={imageStyle} />;
    }
  };

  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 48 48"
      xmlns="http://www.w3.org/2000/svg"
      style={{ filter: 'drop-shadow(0px 2px 4px rgba(0,0,0,0.3))' }}
    >
      {/* Red Triangle */}
      <path
        d="M24 4L4 40H44L24 4Z"
        fill="white"
        stroke="#e11d48"
        strokeWidth="3"
        strokeLinejoin="round"
      />
      {/* Pictogram Area */}
      <g transform="translate(0, 2)">
        {getPictogram()}
      </g>
    </svg>
  );
};

export default StreetSign;
