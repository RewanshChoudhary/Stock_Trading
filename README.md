# Stock Trading Application

## Overview
A robust stock trading platform built with Spring Boot that enables users to manage and analyze stock market investments. The application provides real-time stock tracking, portfolio management, and secure trading capabilities.

## Features
- User Authentication & Authorization
- Real-time Stock Price Tracking
- Portfolio Management
- Transaction History
- Watchlist Creation and Management
- Stock Analytics and Reporting
- WebSocket Integration for Real-time Updates

## Technology Stack
- Backend: Spring Boot (Java)
- Database: MySQL/PostgreSQL
- Security: Spring Security with BCrypt password encoding
- Real-time Communication: WebSocket
- Build Tool: Maven
- Testing: JUnit, Spring Test

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

## API Endpoints

### Authentication
```
POST /users/register
- Register new user
- Body: { "username": string, "email": string, "password": string }

POST /users/login
- User login
- Body: { "username": string, "password": string }
```

### User Operations
```
GET /users/me
- Get current user profile

GET /users/me/positions
- Get user's current stock positions

GET /users/me/orders
- Get user's open orders

GET /users/me/trades
- Get user's trade history
```

### Stock Operations
```
GET /stocks
- Get all available stocks

PUT /stocks/{stockId}/price
- Update stock price
- Body: { "price": number }
```

### Portfolio Management
```
GET /portfolio
- Get user's portfolio summary

POST /portfolio/positions
- Create new position
- Body: { "stockId": number, "quantity": number }

PUT /portfolio/positions/{positionId}
- Update existing position
- Body: { "quantity": number }
```

## Project Structure
```
Stock_Trading/
├── src/
│   ├── main/
│   │   ├── java/
│   │   │   └── com/example/stocktrading/
│   │   │       ├── controller/
│   │   │       ├── model/
│   │   │       ├── repository/
│   │   │       ├── service/
│   │   │       └── dto/
│   │   └── resources/
│   └── test/
│       └── java/
├── .mvn/
├── node_modules/
└── websocket-client.js
```

## Installation

1. Clone the repository
```bash
git clone https://github.com/RewanshChoudhary/Stock_Trading.git
cd Stock_Trading
```

2. Configure Database
- Create a MySQL/PostgreSQL database
- Update `application.properties` with your database credentials

3. Install Dependencies
```bash
mvn install
```

4. Run the Application
```bash
mvn spring-boot:run
```

5. WebSocket Client (Optional)
```bash
npm install
node websocket-client.js
```

## Contributing
1. Fork the repository
2. Create your feature branch (`git checkout -b feature/amazing-feature`)
3. Commit your changes (`git commit -m 'Add some amazing feature'`)
4. Push to the branch (`git push origin feature/amazing-feature`)
5. Open a Pull Request

## License
This project is licensed under the MIT License

## Contact
Rewansh Choudhary
Project Link: https://github.com/RewanshChoudhary/Stock_Trading