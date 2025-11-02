# Stock Trading Application

## Overview
This is a stock trading application that helps users manage and analyze stock market investments.

## Entity Relationship Diagram (ERD)
```mermaid
erDiagram
    USER {
        int user_id PK
        string username
        string email
        string password_hash
        datetime created_at
        datetime last_login
    }
    
    PORTFOLIO {
        int portfolio_id PK
        int user_id FK
        string name
        decimal total_value
        datetime created_at
        datetime updated_at
    }
    
    STOCK {
        string symbol PK
        string company_name
        string description
        string sector
        decimal current_price
        datetime last_updated
    }
    
    TRANSACTION {
        int transaction_id PK
        int portfolio_id FK
        string stock_symbol FK
        string type
        int quantity
        decimal price
        datetime transaction_date
    }
    
    WATCHLIST {
        int watchlist_id PK
        int user_id FK
        string name
        datetime created_at
    }
    
    WATCHLIST_ITEM {
        int watchlist_id FK
        string stock_symbol FK
        datetime added_at
    }

    USER ||--o{ PORTFOLIO : "has"
    USER ||--o{ WATCHLIST : "creates"
    PORTFOLIO ||--o{ TRANSACTION : "contains"
    WATCHLIST ||--o{ WATCHLIST_ITEM : "includes"
    STOCK ||--o{ TRANSACTION : "involved_in"
    STOCK ||--o{ WATCHLIST_ITEM : "listed_in"
```

## Features
- User authentication and authorization
- Portfolio management
- Real-time stock tracking
- Transaction history
- Watchlist creation and management
- Stock analytics and reporting

## Technology Stack
- Backend: [Your backend technology]
- Frontend: [Your frontend technology]
- Database: [Your database system]
- Stock Data API: [Your chosen stock API]

## Installation

1. Clone the repository
```bash
git clone https://github.com/RewanshChoudhary/Stock_Trading.git
cd Stock_Trading
```

2. Install dependencies
```bash
# Add installation commands based on your tech stack
```

3. Configure environment variables
```bash
# Create .env file and add necessary configurations
```

4. Run the application
```bash
# Add commands to run your application
```

## Project Structure
```
Stock_Trading/
├── src/
│   ├── controllers/
│   ├── models/
│   ├── services/
│   └── utils/
├── tests/
├── config/
└── docs/
```

## API Documentation
- `/api/auth` - Authentication endpoints
- `/api/portfolio` - Portfolio management
- `/api/stocks` - Stock information and trading
- `/api/watchlist` - Watchlist operations

## Contributing
1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License
This project is licensed under the MIT License - see the LICENSE file for details.

## Contact
Rewansh Choudhary - [@YourTwitter](https://twitter.com/YourTwitter)
Project Link: [https://github.com/RewanshChoudhary/Stock_Trading](https://github.com/RewanshChoudhary/Stock_Trading)