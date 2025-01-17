import React from "react";

export const WeightFill4 = ({ className }) => {
  return (
    <svg
      className={`weight-fill-4 ${className}`}
      fill="none"
      height="32"
      viewBox="0 0 32 32"
      width="32"
      xmlns="http://www.w3.org/2000/svg"
    >
      <path
        className="path"
        d="M25 3H7C6.46957 3 5.96086 3.21071 5.58579 3.58579C5.21071 3.96086 5 4.46957 5 5V27C5 27.5304 5.21071 28.0391 5.58579 28.4142C5.96086 28.7893 6.46957 29 7 29H25C25.5304 29 26.0391 28.7893 26.4142 28.4142C26.7893 28.0391 27 27.5304 27 27V5C27 4.46957 26.7893 3.96086 26.4142 3.58579C26.0391 3.21071 25.5304 3 25 3ZM20 13H25V16H20V13ZM14 18H25V21H14V18ZM25 27H7V23H25V27Z"
        fill="#343330"
      />
    </svg>
  );
};
